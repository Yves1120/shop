package com.spzx.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.user.domain.UpdateUserLogin;
import com.spzx.user.domain.UserAddress;
import com.spzx.user.domain.UserBrowseHistory;
import com.spzx.user.domain.UserCollect;
import com.spzx.user.domain.UserInfo;
import com.spzx.user.mapper.UserAddressMapper;
import com.spzx.user.mapper.UserBrowseHistoryMapper;
import com.spzx.user.mapper.UserCollectMapper;
import com.spzx.user.mapper.UserInfoMapper;
import com.spzx.user.service.IUserInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会员Service业务层处理
 *
 * @author atguigu
 * @date 2024-09-27
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {
    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private UserAddressMapper userAddressMapper;

    @Autowired
    private UserCollectMapper userCollectMapper;

    @Autowired
    private UserBrowseHistoryMapper userBrowseHistoryMapper;

    /**
     * 查询会员列表
     *
     * @param userInfo 会员
     * @return 会员
     */
    @Override
    public List<UserInfo> selectUserInfoList(UserInfo userInfo) {
        return userInfoMapper.selectUserInfoList(userInfo);
    }


    @Override
    public List<UserAddress> selectUserAddressList(Long userId) {
        List<UserAddress> userAddresses = userAddressMapper.selectList(new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, userId));
        return userAddresses;
    }


    @Override
    public void register(UserInfo userInfo) {
        long count = userInfoMapper.selectCount(new LambdaQueryWrapper<UserInfo>().eq(UserInfo::getUsername, userInfo.getUsername()));
        if (count > 0) {
            throw new ServiceException("手机号已经存在");
        }
        userInfo.setStatus(1); //Long 修改成 Integer
        userInfo.setSex(0);
        userInfo.setAvatar("http://thirdwx.qlogo.cn/mmopen/vi_32/DYAIOgq83eoj0hHXhgJNOTSOFsS4uZs8x1ConecaVOB8eIl115xmJZcT4oCicvia7wMEufibKtTLqiaJeanU2Lpg3w/132");

        userInfoMapper.insert(userInfo);
    }


    @Override
    public UserInfo selectUserByUserName(String username) {
        return userInfoMapper.selectOne(new LambdaQueryWrapper<UserInfo>().eq(UserInfo::getUsername, username));
    }

    @Override
    public Boolean updateUserLogin(UpdateUserLogin updateUserLogin) {
        UserInfo userInfo = new UserInfo();
        userInfo.setId(updateUserLogin.getUserId());
        userInfo.setLastLoginIp(updateUserLogin.getLastLoginIp());
        userInfo.setLastLoginTime(updateUserLogin.getLastLoginTime());
        userInfoMapper.updateById(userInfo);
        return true;
    }

    @Override
    public Boolean isCollect(Long skuId, Long userId) {
        long count = userCollectMapper.selectCount(
                new LambdaQueryWrapper<UserCollect>()
                        .eq(UserCollect::getUserId, userId)
                        .eq(UserCollect::getSkuId, skuId)
                        .eq(UserCollect::getDelFlag, "0")
        );
        return count > 0;
    }

    @Override
    public void collect(Long skuId, Long userId) {
        // 去重：如果已收藏（含已取消的），更新delFlag即可
        UserCollect existing = userCollectMapper.selectOne(
                new LambdaQueryWrapper<UserCollect>()
                        .eq(UserCollect::getUserId, userId)
                        .eq(UserCollect::getSkuId, skuId)
        );
        if (existing != null) {
            existing.setDelFlag("0");
            userCollectMapper.updateById(existing);
        } else {
            UserCollect userCollect = new UserCollect();
            userCollect.setUserId(userId);
            userCollect.setSkuId(skuId);
            userCollectMapper.insert(userCollect);
        }
    }

    @Override
    public void cancelCollect(Long skuId, Long userId) {
        userCollectMapper.update(null,
                new LambdaUpdateWrapper<UserCollect>()
                        .eq(UserCollect::getUserId, userId)
                        .eq(UserCollect::getSkuId, skuId)
                        .set(UserCollect::getDelFlag, "2")
        );
    }

    @Override
    public PageInfo<UserCollect> userCollectList(Integer pageNo, Integer pageSize, Long userId) {
        PageHelper.startPage(pageNo, pageSize);
        List<UserCollect> list = userCollectMapper.selectList(
                new LambdaQueryWrapper<UserCollect>()
                        .eq(UserCollect::getUserId, userId)
                        .eq(UserCollect::getDelFlag, "0")
                        .orderByDesc(UserCollect::getCreateTime)
        );
        return new PageInfo<>(list);
    }

    @Override
    public PageInfo<UserBrowseHistory> userBrowseHistoryList(Integer pageNo, Integer pageSize, Long userId) {
        PageHelper.startPage(pageNo, pageSize);
        List<UserBrowseHistory> list = userBrowseHistoryMapper.selectList(
                new LambdaQueryWrapper<UserBrowseHistory>()
                        .eq(UserBrowseHistory::getUserId, userId)
                        .eq(UserBrowseHistory::getDelFlag, "0")
                        .orderByDesc(UserBrowseHistory::getCreateTime)
        );
        return new PageInfo<>(list);
    }

    @Override
    public void saveBrowseHistory(Long skuId, Long userId) {
        UserBrowseHistory userBrowseHistory = new UserBrowseHistory();
        userBrowseHistory.setUserId(userId);
        userBrowseHistory.setSkuId(skuId);
        userBrowseHistoryMapper.insert(userBrowseHistory);
    }
}
