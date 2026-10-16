package com.spzx.cart.controller;

import com.spzx.cart.api.domain.CartInfo;
import com.spzx.cart.service.ICartService;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.web.controller.BaseController;
import com.spzx.common.security.annotation.InnerAuth;
import com.spzx.common.security.annotation.RequiresLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "购物车接口")
@RestController
@RequestMapping()
public class CartController extends BaseController {

    @Autowired
    private ICartService cartService;

    @Operation(summary = "添加商品到购物车")
    @RequiresLogin
    @GetMapping("/addToCart/{skuId}")
    public R addToCart(@PathVariable Long skuId) {
        cartService.addToCart(skuId, 1);
        return R.ok();
    }

    @Operation(summary = "获取购物车列表")
    @RequiresLogin
    @GetMapping("/cartList")
    public R<List<CartInfo>> cartList() {
        List<CartInfo> cartList = cartService.getCartList();
        return R.ok(cartList);
    }

    @Operation(summary = "删除购物车商品")
    @RequiresLogin
    @DeleteMapping("/deleteCart/{skuId}")
    public R deleteCart(@PathVariable Long skuId) {
        cartService.deleteCart(skuId);
        return R.ok();
    }

    @Operation(summary = "更新购物车商品数量")
    @RequiresLogin
    @PutMapping("/updateCartNum/{skuId}/{skuNum}")
    public R updateCartNum(@PathVariable Long skuId, @PathVariable Integer skuNum) {
        cartService.updateCartNum(skuId, skuNum);
        return R.ok();
    }

    @Operation(summary = "选中/取消选中购物车商品")
    @RequiresLogin
    @GetMapping("/checkCart/{skuId}")
    public R checkCart(@PathVariable Long skuId) {
        cartService.checkCart(skuId);
        return R.ok();
    }

    @Operation(summary = "全选/全不选")
    @RequiresLogin
    @GetMapping("/allCheckCart/{isChecked}")
    public R allCheckCart(@PathVariable Integer isChecked) {
        cartService.checkAllCart(isChecked);
        return R.ok();
    }

    @Operation(summary = "获取选中的购物车商品(内部调用)")
    @InnerAuth
    @GetMapping("/getAllChecked")
    public R<List<CartInfo>> getAllChecked(@RequestHeader(SecurityConstants.FROM_SOURCE) String source) {
        List<CartInfo> checkedList = cartService.getAllChecked();
        return R.ok(checkedList);
    }

    @Operation(summary = "清除选中的购物车商品")
    @RequiresLogin
    @GetMapping("/clearCart")
    public R clearCart() {
        cartService.clearCheckedCart();
        return R.ok();
    }

    @Operation(summary = "清除选中的购物车商品(内部调用)")
    @InnerAuth
    @DeleteMapping("/clearCheckedCart")
    public R clearCheckedCart(@RequestHeader(SecurityConstants.FROM_SOURCE) String source) {
        cartService.clearCheckedCart();
        return R.ok();
    }
}
