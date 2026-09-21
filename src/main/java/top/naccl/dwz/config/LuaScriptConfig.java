package top.naccl.dwz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class LuaScriptConfig {

	@Bean
	public RedisScript<Long> releaseLockScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("lua/release_lock.lua"));
		script.setResultType(Long.class);
		return script;
	}

	@Bean
	public RedisScript<Long> rateLimitScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("lua/rate_limit.lua"));
		script.setResultType(Long.class);
		return script;
	}
}
