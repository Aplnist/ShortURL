package top.naccl.dwz.config;

import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

	@Value("${spring.redis.host}")
	private String host;

	@Value("${spring.redis.port}")
	private int port;

	@Value("${spring.redis.password:}")
	private String password;

	@Value("${spring.redis.database}")
	private int database;

	@Bean(destroyMethod = "shutdown")
	public RedissonClient redissonClient() {
		Config config = new Config();
		SingleServerConfig serverConfig = config.useSingleServer()
				.setAddress("redis://" + host + ":" + port)
				.setDatabase(database);
		if (password != null && !password.isEmpty()) {
			serverConfig.setPassword(password);
		}
		return Redisson.create(config);
	}

	@Bean
	public RBloomFilter<String> shortUrlBloomFilter(RedissonClient redissonClient) {
		RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("shortUrlBloomFilter", StringCodec.INSTANCE);
		// 100万预期元素、0.1%误判率；若Redis中已存在则保留原有配置
		bloomFilter.tryInit(1_000_000L, 0.001);
		return bloomFilter;
	}
}
