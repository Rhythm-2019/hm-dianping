package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCodeToPhone(HttpSession session, String phone);

    Result login(HttpSession session, LoginFormDTO loginForm);

    List<UserDTO> queryByIds(List<Long> ids);

    void sign(Long id, LocalDateTime date);

    int signCountInMount(Long id);

    Long uvCount();
}
