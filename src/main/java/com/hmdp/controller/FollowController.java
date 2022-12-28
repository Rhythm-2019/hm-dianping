package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{follow}")
    public Result follow(@PathVariable("id") Long followId,@PathVariable("follow") Boolean isFollow) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        return followService.follow(UserHolder.getUser().getId(), followId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followId) {
        if (UserHolder.getUser() == null) {
            return Result.ok(false);
        }
        return Result.ok(followService.isFollow(UserHolder.getUser().getId(), followId));
    }

    @GetMapping("/common/{id}")
    public Result getCommonFollowers(@PathVariable("id") Long followid) {
        if (UserHolder.getUser() == null) {
            return Result.ok(Collections.emptyList());
        }
        return Result.ok(followService.getCommonFollowers(followid, UserHolder.getUser().getId()));
    }


}
