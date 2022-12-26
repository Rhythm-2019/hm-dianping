package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import io.netty.util.CharsetUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Rhythm-2019
 * @date 2022/12/26
 * @description
 */
@SpringBootTest
@RunWith(SpringRunner.class)
public class VoucherOrderServiceImplTest {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void prepareData() {
        List<String> tokenList = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            // 创建 1000 个用户
            User user = new User();
            user.setNickName("User_" + RandomUtil.randomString(10));
            user.setPhone(RandomUtil.randomNumbers(11));
            userService.save(user);
            // 保存 token 到 redis 中
            String token = UUID.randomUUID().toString(true);

            // 保存用户数据到 Redis 中
            String userDTOKey = RedisConstants.LOGIN_USER_KEY + token;
            Map<String, Object> userMap = BeanUtil.beanToMap(BeanUtil.copyProperties(user, UserDTO.class),
                    new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((name, value) -> value.toString()));
            stringRedisTemplate.opsForHash().putAll(userDTOKey, userMap);
            stringRedisTemplate.expire(userDTOKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

            tokenList.add(token);
        }
        // 到处所有的 token 到文件中
        FileUtil.appendLines(tokenList, new File("tokens.txt"), CharsetUtil.UTF_8);
    }
}