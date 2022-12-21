package com.hmdp.config;

import com.hmdp.intercceptor.LoginInterceptor;
import com.hmdp.intercceptor.RefreshUserTokenExpireInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

import javax.annotation.Resource;

/**
 * @author Rhythm-2019
 * @date 2022/12/21
 */
@Configuration
public class WebMvcConfig  extends WebMvcConfigurationSupport {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    protected void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshUserTokenExpireInterceptor(stringRedisTemplate));
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/voucher/**",
                        "/upload/**",
                        "/shop/**",
                        "/shop-type/**",
                        "/blog/hot",
                        "/user/login",
                        "/user/code"
                );
    }
}
