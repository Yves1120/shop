package com.spzx.cart.service.impl;

import com.alibaba.fastjson2.JSON;
import com.spzx.cart.api.domain.CartInfo;
import com.spzx.cart.service.ICartService;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.domain.R;
import com.spzx.common.security.utils.SecurityUtils;
import com.spzx.product.api.RemoteProductService;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.product.api.domain.vo.SkuPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CartServiceImpl implements ICartService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private RemoteProductService remoteProductService;

    private String getCartKey(Long userId) {
        return "user:cart:" + userId;
    }

    @Override
    public void addToCart(Long skuId, Integer skuNum) {
        Long userId = SecurityUtils.getUserId();
        String cartKey = getCartKey(userId);
        HashOperations<String, String, String> hashOperations = redisTemplate.opsForHash();

        String cartInfoStr = hashOperations.get(cartKey, String.valueOf(skuId));
        if (cartInfoStr != null) {
            CartInfo cartInfo = JSON.parseObject(cartInfoStr, CartInfo.class);
            cartInfo.setSkuNum(cartInfo.getSkuNum() + skuNum);
            hashOperations.put(cartKey, String.valueOf(skuId), JSON.toJSONString(cartInfo));
        } else {
            R<ProductSku> skuResult = remoteProductService.getProductSku(skuId, SecurityConstants.INNER);
            if (R.SUCCESS == skuResult.getCode() && skuResult.getData() != null) {
                ProductSku productSku = skuResult.getData();
                CartInfo cartInfo = new CartInfo();
                cartInfo.setUserId(userId);
                cartInfo.setSkuId(skuId);
                cartInfo.setCartPrice(productSku.getSalePrice());
                cartInfo.setSkuPrice(productSku.getSalePrice());
                cartInfo.setSkuNum(skuNum);
                cartInfo.setThumbImg(productSku.getThumbImg());
                cartInfo.setSkuName(productSku.getSkuName());
                cartInfo.setIsChecked(1);
                hashOperations.put(cartKey, String.valueOf(skuId), JSON.toJSONString(cartInfo));
            }
        }
    }

    @Override
    public List<CartInfo> getCartList() {
        Long userId = SecurityUtils.getUserId();
        String cartKey = getCartKey(userId);
        HashOperations<String, String, String> hashOperations = redisTemplate.opsForHash();

        List<String> values = hashOperations.values(cartKey);
        List<CartInfo> cartInfoList = new ArrayList<>();
        if (!CollectionUtils.isEmpty(values)) {
            cartInfoList = values.stream()
                    .map(str -> JSON.parseObject(str, CartInfo.class))
                    .collect(Collectors.toList());

            // 批量获取最新价格
            List<Long> skuIdList = cartInfoList.stream()
                    .map(CartInfo::getSkuId)
                    .collect(Collectors.toList());
            if (!CollectionUtils.isEmpty(skuIdList)) {
                R<List<SkuPrice>> priceResult = remoteProductService.getSkuPriceList(skuIdList, SecurityConstants.INNER);
                if (R.SUCCESS == priceResult.getCode() && priceResult.getData() != null) {
                    List<SkuPrice> skuPriceList = priceResult.getData();
                    for (CartInfo cartInfo : cartInfoList) {
                        for (SkuPrice skuPrice : skuPriceList) {
                            if (cartInfo.getSkuId().equals(skuPrice.getSkuId())) {
                                cartInfo.setSkuPrice(skuPrice.getSalePrice());
                                break;
                            }
                        }
                    }
                    // 更新Redis中的价格
                    for (CartInfo cartInfo : cartInfoList) {
                        hashOperations.put(cartKey, String.valueOf(cartInfo.getSkuId()), JSON.toJSONString(cartInfo));
                    }
                }
            }
        }
        return cartInfoList;
    }

    @Override
    public void deleteCart(Long skuId) {
        Long userId = SecurityUtils.getUserId();
        String cartKey = getCartKey(userId);
        redisTemplate.opsForHash().delete(cartKey, String.valueOf(skuId));
    }

    @Override
    public void updateCartNum(Long skuId, Integer skuNum) {
        Long userId = SecurityUtils.getUserId();
        String cartKey = getCartKey(userId);
        HashOperations<String, String, String> hashOperations = redisTemplate.opsForHash();

        String cartInfoStr = hashOperations.get(cartKey, String.valueOf(skuId));
        if (cartInfoStr != null) {
            CartInfo cartInfo = JSON.parseObject(cartInfoStr, CartInfo.class);
            cartInfo.setSkuNum(skuNum);
            hashOperations.put(cartKey, String.valueOf(skuId), JSON.toJSONString(cartInfo));
        }
    }

    @Override
    public void checkCart(Long skuId) {
        Long userId = SecurityUtils.getUserId();
        String cartKey = getCartKey(userId);
        HashOperations<String, String, String> hashOperations = redisTemplate.opsForHash();

        String cartInfoStr = hashOperations.get(cartKey, String.valueOf(skuId));
        if (cartInfoStr != null) {
            CartInfo cartInfo = JSON.parseObject(cartInfoStr, CartInfo.class);
            // 切换选中状态：1→0，0→1
            cartInfo.setIsChecked(cartInfo.getIsChecked() == 1 ? 0 : 1);
            hashOperations.put(cartKey, String.valueOf(skuId), JSON.toJSONString(cartInfo));
        }
    }

    @Override
    public void checkAllCart(Integer isChecked) {
        Long userId = SecurityUtils.getUserId();
        String cartKey = getCartKey(userId);
        HashOperations<String, String, String> hashOperations = redisTemplate.opsForHash();

        List<String> values = hashOperations.values(cartKey);
        if (!CollectionUtils.isEmpty(values)) {
            for (String value : values) {
                CartInfo cartInfo = JSON.parseObject(value, CartInfo.class);
                cartInfo.setIsChecked(isChecked);
                hashOperations.put(cartKey, String.valueOf(cartInfo.getSkuId()), JSON.toJSONString(cartInfo));
            }
        }
    }

    @Override
    public List<CartInfo> getAllChecked() {
        Long userId = SecurityUtils.getUserId();
        String cartKey = getCartKey(userId);
        HashOperations<String, String, String> hashOperations = redisTemplate.opsForHash();

        List<String> values = hashOperations.values(cartKey);
        if (!CollectionUtils.isEmpty(values)) {
            return values.stream()
                    .map(str -> JSON.parseObject(str, CartInfo.class))
                    .filter(cartInfo -> cartInfo.getIsChecked() == 1)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @Override
    public void clearCheckedCart() {
        Long userId = SecurityUtils.getUserId();
        String cartKey = getCartKey(userId);
        HashOperations<String, String, String> hashOperations = redisTemplate.opsForHash();

        List<String> values = hashOperations.values(cartKey);
        if (!CollectionUtils.isEmpty(values)) {
            List<CartInfo> checkedList = values.stream()
                    .map(str -> JSON.parseObject(str, CartInfo.class))
                    .filter(cartInfo -> cartInfo.getIsChecked() == 1)
                    .collect(Collectors.toList());
            for (CartInfo cartInfo : checkedList) {
                hashOperations.delete(cartKey, String.valueOf(cartInfo.getSkuId()));
            }
        }
    }
}
