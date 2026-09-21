# 短链接服务

长链接生成短链接，访问短链接 302 重定向至原始长链接

| 依赖        | 说明                                  |
| ----------- | ------------------------------------- |
| Spring Boot | MVC 框架                              |
| thymeleaf   | 模板引擎                              |
| MyBatis     | ORM 框架                              |
| Redis       | 缓存、分布式锁、Lua 限流               |
| Redisson    | Redis 客户端、RBloomFilter 布隆过滤器  |
| hutool      | MurmurHash 哈希算法                   |

## 核心特性

- 基于 MurmurHash + Base62 生成短码，冲突时追加后缀重新哈希
- 基于 Redisson RBloomFilter 的布隆过滤器，防止缓存穿透
- Redis 缓存 + 空值缓存，减轻数据库压力
- Redis 分布式锁（SETNX + Lua 原子释放），防止缓存击穿
- Lua 脚本实现原子限流，防止接口被高频调用
- 启动时从数据库预热布隆过滤器（仅当 Redis 中过滤器为空）

## 快速开始

### 环境要求

- JDK 1.8+
- Maven 3.6+
- MySQL 5.7 / 8.0
- Redis 5.0+

### 1. 建库建表

项目根目录提供 `dwz.sql` 建表脚本，先创建数据库再导入：

```sql
CREATE DATABASE dwz DEFAULT CHARACTER SET utf8mb4;
```

```bash
mysql -u root -p dwz < dwz.sql
```

表结构 `url_map`：

| 字段         | 类型         | 说明               |
| ------------ | ------------ | ------------------ |
| id           | bigint       | 主键自增           |
| surl         | varchar(255) | 短码（唯一索引）   |
| lurl         | varchar(1000)| 原始长链接         |
| views        | int          | 访问次数           |
| create_time  | datetime     | 创建时间           |

### 2. 配置数据库 / Redis

配置集中在 `src/main/resources/application-dev.properties`，密码通过环境变量注入（**请勿硬编码提交**）：

| 环境变量       | 默认值    | 说明        |
| -------------- | --------- | ----------- |
| DB_PASSWORD    | 空        | MySQL 密码  |
| REDIS_HOST     | localhost | Redis 地址  |
| REDIS_PASSWORD | 空        | Redis 密码  |

本地 MySQL / Redis 无密码时无需设置；有密码时：

```bash
# Linux / macOS
export DB_PASSWORD=你的密码
export REDIS_PASSWORD=你的密码

# Windows (PowerShell)
$env:DB_PASSWORD="你的密码"
$env:REDIS_PASSWORD="你的密码"
```

### 3. 启动

```bash
mvn spring-boot:run
```

默认激活 `dev` 环境，启动后访问 <http://localhost:8060>。

## 接口说明

### 生成短链接

`POST /generate`（Content-Type: application/json）

请求体：

```json
{ "longURL": "https://github.com/Naccl/ShortURL" }
```

成功响应：

```json
{ "code": 200, "msg": "请求成功", "data": "http://localhost:8060/xYz123" }
```

失败响应（URL 非法）：

```json
{ "code": 400, "msg": "URL有误" }
```

> 该接口有访问频率限制（`@AccessLimit`），同一 IP 10 秒内只能调用一次，超出返回 403。

curl 示例：

```bash
curl -X POST http://localhost:8060/generate \
  -H "Content-Type: application/json" \
  -d '{"longURL":"https://github.com/Naccl/ShortURL"}'
```

### 访问短链接

`GET /{shortURL}`：命中则 302 重定向到原始长链接并异步自增访问量；未命中重定向回首页。

## 实现

生成短链接时，使用 MurmurHash 算法将原始长链接 hash 为 32 位散列值，再转为 62 进制字符串作为短码。为避免哈希冲突，先通过布隆过滤器判断该短码是否可能已被占用，若命中则查数据库做权威确认：已被占用则追加后缀重新哈希，未被占用则直接落库，写入数据库、加入布隆过滤器并添加 Redis 缓存。

访问短链接时，先通过布隆过滤器前置拦截不存在的短码，再查询 Redis 缓存；缓存未命中则通过分布式锁保证只有一个线程回源数据库，避免缓存击穿，同时缓存空值防止穿透，最后 302 重定向至原始长链接并异步自增访问量。

![短链路项目流程图](./flow.png)

## 技术选型

### MurmurHash

长链转短链需要一个哈希算法。应用类型决定了我们不需要解密，而更关心运算速度与冲突概率。MurmurHash 是一种非加密型哈希算法，与 MD5、SHA 等常见哈希函数相比，性能与随机分布特征更佳。MurmurHash 有 32/64/128 bit 实现，32 bit 已足够表示近 43 亿个短链接，这里使用 hutool 的实现。

### Base62

MurmurHash 生成的散列值为 32 位整数，转为 62 进制后最长为 6 个字符，进一步缩短了短链长度。

### 布隆过滤器（RBloomFilter）

哈希函数不可避免会产生冲突。生成短码后，先通过布隆过滤器判断该短码是否可能已被占用：布隆过滤器说不存在则一定不存在，可直接使用；说存在则可能为假阳性，此时再查数据库做权威确认。相比基于 JVM 内存的布隆过滤器，Redisson 的 RBloomFilter 存储在 Redis 中，天然支持分布式部署下的多实例共享，且线程安全、可配置容量与误判率。

### Redis 缓存与防穿透

生成短链接后一段时间内其访问频率较高，因此写入带过期时间的 Redis 缓存以减轻数据库压力。对于布隆过滤器误判为「可能存在」但数据库实际不存在的短码，缓存空值以避免每次都穿透到数据库。

### 分布式锁与防击穿

热点短码缓存过期的瞬间可能被大量请求同时回源数据库（缓存击穿）。使用 Redis SETNX 加锁、Lua 脚本原子释放（校验锁的持有者，防止误删），保证同一时刻只有一个请求回源，其余请求等待后读取回填的缓存。

### Lua 限流

通过 @AccessLimit 注解 + Lua 脚本，将「读取计数、判断、自增、设置过期时间」合并为单条原子命令，避免传统 GET + INCR 的并发窗口问题，防止接口被高频调用。

### 302 状态码

301 为永久重定向、302 为临时重定向。通常需要记录访问次数或需要修改、删除短链接时，使用 302 临时重定向来处理，和服务器压力相比，数据的价值往往更大。

## 参考与致谢

本项目骨架基于 [Naccl/ShortURL](https://github.com/Naccl/ShortURL) 二次开发，感谢原作者的贡献（MIT License，版权声明见 [LICENSE](./LICENSE)）。

在原始单机版核心流程之上，本版本补充了分布式与缓存相关的完整改造：

- Redis 缓存 + 空值缓存，防缓存穿透
- Redisson RBloomFilter 布隆过滤器，防缓存穿透，启动时从数据库预热
- Redis 分布式锁（SETNX + Lua 原子释放），防缓存击穿
- Lua 脚本实现原子限流，防接口高频调用
- 异步线程池自增访问量，避免阻塞主线程
- 单元测试 + JMeter 压力测试
