package com.aplnist.dwz.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.aplnist.dwz.interceptor.AccessLimitInterceptor;

/**
 * @Description: 配置CORS跨域支持、拦截器
 * @Author: Aplnist
 * @Date: 2020-09-16
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
	@Autowired
	AccessLimitInterceptor accessLimitInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(accessLimitInterceptor);
	}
}
