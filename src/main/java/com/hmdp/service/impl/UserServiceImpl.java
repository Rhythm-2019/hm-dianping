package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCodeToPhone(HttpSession session, String phone) {

        // 检查手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号码格式错误");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 将验证码保存到 session/redis 中
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // TODO 发送验证码，这里只是一个模拟，发生远程调用的时候需要注意日志和 timeout 的处理
        log.debug(String.format("send a code %s to phone %s", code, phone));

        // 思考一下：使用 Redis 和 Session 的利弊在哪？
        // 如果代码走到折柳宕机了，用户收到了验证码填写提交会发现校验失败，这样会影响用户体验

        // 返回发送成功
        return Result.ok();
    }

    @Override
    public Result login(HttpSession session, LoginFormDTO loginForm) {
        // 检查手机号是否合法
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号码格式错误");
        }
        if (StringUtils.isEmpty(loginForm.getCode())) {
            return loginWithPassword(session, loginForm);
        }
        return loginWithCode(session, loginForm);
    }

    @Override
    public List<UserDTO> queryByIds(List<Long> ids) {
        return query().in("id", ids)
                .last(String.format("ORDER BY FIELD(id, %s)", StrUtil.join(",", ids)))
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
    }

    private Result loginWithCode(HttpSession session, LoginFormDTO loginForm) {
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        if (StrUtil.isBlank(code)) {
            return Result.fail("服务异常，请重新获取验证码");
        }
        System.out.println(code);
        if (!code.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }

        User user = this.getOne(new LambdaQueryWrapper<>(User.class).eq(User::getPhone, loginForm.getPhone()));
        if (user == null) {
            user = createDefaultUserWithPhone(loginForm.getPhone());
        }
        // 生成 Token
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

        return Result.ok(token);
    }

    private User createDefaultUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("User_" + RandomUtil.randomString(10));

        this.save(user);

        return user;
    }

    private Result loginWithPassword(HttpSession session, LoginFormDTO loginForm) {
        return Result.fail("功能未实现");
    }
}
