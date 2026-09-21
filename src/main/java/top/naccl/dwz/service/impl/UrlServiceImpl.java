package top.naccl.dwz.service.impl;

import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import top.naccl.dwz.entity.UrlMap;
import top.naccl.dwz.mapper.UrlMapper;
import top.naccl.dwz.service.UrlService;
import top.naccl.dwz.util.HashUtils;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @Description: 长短链接映射业务层实现
 * @Author: Aplnist
 * @Date: 2026-08-4
 */
@Service
public class UrlServiceImpl implements UrlService {
	@Autowired
	UrlMapper urlMapper;
	@Autowired
	StringRedisTemplate redisTemplate;
	@Autowired
	RedisScript<Long> releaseLockScript;
	@Autowired
	RBloomFilter<String> bloomFilter;
	//自定义长链接防重复字符串
	private static final String DUPLICATE = "*";
	//最近使用的短链接缓存过期时间(分钟)
	private static final long TIMEOUT = 10;
	//短链接碰撞重试上限，避免理论上的无限递归导致栈溢出
	private static final int MAX_ATTEMPTS = 10;

	@Override
	public String getLongUrlByShortUrl(String shortURL) {
		// Bloom过滤器前置拦截，防止缓存穿透
		if (!bloomFilter.contains(shortURL)) {
			return null;
		}
		//查找Redis中是否有缓存
		String longURL = redisTemplate.opsForValue().get(shortURL);
		if (longURL != null) {
			//空字符串""表示已缓存"不存在"，直接返回null防穿透
			if (longURL.isEmpty()) {
				return null;
			}
			redisTemplate.expire(shortURL, TIMEOUT, TimeUnit.MINUTES);
			return longURL;
		}
		//缓存未命中，循环抢锁（最多3次），避免递归栈溢出
		String lockKey = "lock:" + shortURL;
		String lockValue = UUID.randomUUID().toString();
		for (int i = 0; i < 3; i++) {
			Boolean locked = false;
			try {
				locked = redisTemplate.opsForValue()
						.setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
				if (Boolean.TRUE.equals(locked)) {
					try {
						//双重检查：可能其他线程已经回填了缓存
						longURL = redisTemplate.opsForValue().get(shortURL);
						if (longURL != null) {
							return longURL.isEmpty() ? null : longURL;
						}
						//从数据库查找
						longURL = urlMapper.getLongUrlByShortUrl(shortURL);
						if (longURL != null) {
							redisTemplate.opsForValue().set(shortURL, longURL, TIMEOUT, TimeUnit.MINUTES);
						} else {
							//缓存空值防穿透，1分钟过期
							redisTemplate.opsForValue().set(shortURL, "", 1, TimeUnit.MINUTES);
						}
						return longURL;
					} finally {
						//Lua脚本原子删锁：校验value，防止误删其他线程的锁
						redisTemplate.execute(releaseLockScript, Collections.singletonList(lockKey), lockValue);
					}
				}
			} catch (Exception e) {
				//Redis异常降级查库
				return urlMapper.getLongUrlByShortUrl(shortURL);
			}
			//没抢到锁，短暂等待后进入下一轮循环
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();// 恢复中断标记，向上传递中断信息
				break;// 停止自旋抢锁，不再重试
			}
		}
		//超过最大重试次数，降级直接查库
		longURL = urlMapper.getLongUrlByShortUrl(shortURL);
		if (longURL != null) {
			redisTemplate.opsForValue().set(shortURL, longURL, TIMEOUT, TimeUnit.MINUTES);
		} else {
			redisTemplate.opsForValue().set(shortURL, "", 1, TimeUnit.MINUTES);
		}
		return longURL;
	}

	@Override
	public String saveUrlMap(String shortURL, String longURL, String originalURL) {
		//碰撞重试改为有界循环，避免理论上的无限递归导致栈溢出
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			//保留长度为1的短链接
			if (shortURL.length() == 1) {
				longURL += DUPLICATE;
				shortURL = HashUtils.hashToBase62(longURL);
				continue;
			}
			//在布隆过滤器中查找是否存在（存在假阳性）
			if (bloomFilter.contains(shortURL)) {
				//先查Redis缓存，快速处理"同一长链接最近刚生成过"的幂等场景
				String redisLongURL = redisTemplate.opsForValue().get(shortURL);
				if (redisLongURL != null && originalURL.equals(redisLongURL)) {
					redisTemplate.expire(shortURL, TIMEOUT, TimeUnit.MINUTES);
					return shortURL;
				}
				//Redis未命中（假阳性、缓存过期或真实冲突），查库确认权威结果
				String dbLongURL = urlMapper.getLongUrlByShortUrl(shortURL);
				if (dbLongURL != null) {
					if (originalURL.equals(dbLongURL)) {
						//同一长链接已存在但缓存过期，回填缓存后幂等返回
						redisTemplate.opsForValue().set(shortURL, originalURL, TIMEOUT, TimeUnit.MINUTES);
						return shortURL;
					}
					//真实冲突：短码被不同长链接占用，追加后缀重新hash
					longURL += DUPLICATE;
					shortURL = HashUtils.hashToBase62(longURL);
					continue;
				}
				//dbLongURL == null：假阳性，短码未被占用，走下方落库逻辑
			}
			//不存在，直接存入数据库
			try {
				urlMapper.saveUrlMap(new UrlMap(shortURL, originalURL));
				bloomFilter.add(shortURL);
				//添加缓存
				redisTemplate.opsForValue().set(shortURL, originalURL, TIMEOUT, TimeUnit.MINUTES);
				return shortURL;
			} catch (Exception e) {
				if (e instanceof DuplicateKeyException) {
					// 数据库已经存在此短链接，在长链接后加上指定字符串，重新hash
					// （校验布隆和写入数据库中间存在时间空隙，被别的线程抢先占用短码）
					longURL += DUPLICATE;
					shortURL = HashUtils.hashToBase62(longURL);
					continue;
				}
				throw e;
			}
		}
		//超过最大重试次数，抛出异常而非继续递归
		throw new RuntimeException("短链接生成失败：重试次数达到上限");
	}

	@Override
	public void updateUrlViews(String shortURL) {
		urlMapper.updateUrlViews(shortURL);
	}
}
