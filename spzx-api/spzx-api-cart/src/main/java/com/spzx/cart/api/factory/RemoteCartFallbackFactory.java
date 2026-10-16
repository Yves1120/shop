package com.spzx.cart.api.factory;

import com.spzx.cart.api.RemoteCartService;
import com.spzx.cart.api.domain.CartInfo;
import com.spzx.common.core.constant.ServiceNameConstants;
import com.spzx.common.core.domain.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RemoteCartFallbackFactory implements FallbackFactory<RemoteCartService> {

    private Logger log = LoggerFactory.getLogger(RemoteCartFallbackFactory.class);

    @Override
    public RemoteCartService create(Throwable throwable) {
        log.error("远程调用服务【{}】出现降级", ServiceNameConstants.CART_SERVICE);
        return new RemoteCartService() {
            @Override
            public R<List<CartInfo>> getAllChecked(String source) {
                return R.fail("获取选中购物车商品失败: " + throwable.getMessage());
            }
            @Override
            public R<Void> clearCheckedCart(String source) {
                return R.fail("清除选中购物车商品失败: " + throwable.getMessage());
            }
        };
    }
}
