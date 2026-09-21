package com.aplnist.dwz.interceptor;

import cn.hutool.json.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import com.aplnist.dwz.annotation.AccessLimit;
import com.aplnist.dwz.entity.R;
import com.aplnist.dwz.util.IpAddressUtils;
import com.aplnist.dwz.util.JacksonUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.Collections;

/**
 * @Description: 访问控制拦截器
 * @Author: Aplnist
 * @Date: 2021-09-16
 */
@Component
public class AccessLimitInterceptor implements HandlerInterceptor {
	@Autowired
	StringRedisTemplate redisTemplate;
	@Autowired
	RedisScript<Long> rateLimitScript;//注入 Lua 脚本对象

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		if (handler instanceof HandlerMethod) {
			//handler 有可能是静态资源等，HandlerMethod才代表Java 接口方法，只对接口方法做注解判断，静态资源直接跳过
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			AccessLimit accessLimit = handlerMethod.getMethodAnnotation(AccessLimit.class);
			if (accessLimit == null) {	//如果方法没有打这个注解 → 不做限流，直接放行
				return true;
			}
			int seconds = accessLimit.seconds();
			int maxCount = accessLimit.maxCount();
			String ip = IpAddressUtils.getIpAddress(request);
			String method = request.getMethod();
			String requestURI = request.getRequestURI();
			String redisKey = ip + ":" + method + ":" + requestURI;

			// Lua脚本原子执行：GET + 计数判断 + INCR + 设过期
			Long result = redisTemplate.execute(rateLimitScript,
					Collections.singletonList(redisKey),
					String.valueOf(maxCount),
					String.valueOf(seconds));
			if (result != null && result == -1) {
				// 当前用户触发了访问频率限制，直接向前端返回 403 JSON 拒绝响应，并终止请求
				response.setContentType("application/json;charset=utf-8");
				PrintWriter out = response.getWriter();
				R r = R.create(403, accessLimit.msg());
				out.write(JacksonUtils.writeValueAsString(r));
				out.flush();
				out.close();
				return false;
			}
		}
		return true;
	}
}
