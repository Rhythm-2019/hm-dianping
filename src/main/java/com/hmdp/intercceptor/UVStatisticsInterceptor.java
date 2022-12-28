package com.hmdp.intercceptor;

import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author Rhythm-2019
 * @date 2022/12/28
 * @description UV 统计
 */
public class UVStatisticsInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public UVStatisticsInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        stringRedisTemplate.opsForHyperLogLog()
                .add(RedisConstants.UV_KEY, request.getRemoteAddr());
        return true;
    }
}
