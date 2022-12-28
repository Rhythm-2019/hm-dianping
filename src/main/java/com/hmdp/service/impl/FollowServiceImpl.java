package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long currentUserId, Long targetUserId, Boolean isFollow) {

        if (isFollow) {
            if (currentUserId.equals(targetUserId)) {
                return Result.fail("自己不能关注自己");
            }
            // 关注
            Follow follow = new Follow();
            follow.setUserId(currentUserId);
            follow.setFollowUserId(targetUserId);
            if (this.save(follow)) {
                stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_KEY + currentUserId, targetUserId.toString());
            }
        } else {
            // 取消关注
            if (this.remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, currentUserId)
                    .eq(Follow::getFollowUserId, targetUserId))) {
                stringRedisTemplate.opsForSet().remove(RedisConstants.FOLLOW_KEY + currentUserId, targetUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public boolean isFollow(Long currentUserId, Long targetUserId) {
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(RedisConstants.FOLLOW_KEY + currentUserId,
                targetUserId.toString());
        if (isMember == null) {
            return this.query()
                    .eq("user_id", targetUserId)
                    .eq("follow_user_id", currentUserId)
                    .count() > 0;
        }
        return BooleanUtil.isTrue(isMember);
    }

    @Override
    public List<UserDTO> getCommonFollowers(Long targetUserId, Long currentUserId) {
        Set<String> userIdSet = stringRedisTemplate.opsForSet().intersect(RedisConstants.FOLLOW_KEY + currentUserId,
                RedisConstants.FOLLOW_KEY + targetUserId);
        if (userIdSet == null || userIdSet.isEmpty()) {
            return Collections.emptyList();
        }
        return userService.queryByIds(CollectionUtil.map(userIdSet, Long::valueOf, true));
    }

}
