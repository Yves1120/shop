package com.spzx.cart.service;

import com.spzx.cart.api.domain.CartInfo;
import java.util.List;

public interface ICartService {
    // 添加商品到购物车
    void addToCart(Long skuId, Integer skuNum);
    // 获取购物车列表
    List<CartInfo> getCartList();
    // 删除购物车商品
    void deleteCart(Long skuId);
    // 更新购物车商品数量
    void updateCartNum(Long skuId, Integer skuNum);
    // 选中/取消选中购物车商品（切换选中状态）
    void checkCart(Long skuId);
    // 全选/全不选
    void checkAllCart(Integer isChecked);
    // 获取选中的购物车商品
    List<CartInfo> getAllChecked();
    // 清除选中的购物车商品
    void clearCheckedCart();
}
