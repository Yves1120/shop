package com.spzx.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spzx.cart.api.RemoteCartService;
import com.spzx.cart.api.domain.CartInfo;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.common.rabbit.constant.MqConst;
import com.spzx.common.rabbit.service.RabbitService;
import com.spzx.common.security.utils.SecurityUtils;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.order.api.domain.OrderItem;
import com.spzx.order.domain.OrderLog;
import com.spzx.order.domain.vo.CheckoutSnapshot;
import com.spzx.order.domain.vo.OrderForm;
import com.spzx.order.domain.vo.TradeVo;
import com.spzx.order.mapper.OrderInfoMapper;
import com.spzx.order.mapper.OrderItemMapper;
import com.spzx.order.mapper.OrderLogMapper;
import com.spzx.order.service.IOrderInfoService;
import com.spzx.product.api.RemoteProductService;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.product.api.domain.vo.SkuPrice;
import com.spzx.user.api.RemoteUserAddressService;
import com.spzx.user.domain.UserAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements IOrderInfoService {

    private static final String TRADE_KEY_PREFIX = "user:trade:";

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private OrderLogMapper orderLogMapper;

    @Autowired
    private RemoteCartService remoteCartService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private RemoteProductService remoteProductService;

    @Autowired
    private RemoteUserAddressService remoteUserAddressService;

    @Autowired
    RabbitService rabbitService;

    @Override
    public List<OrderInfo> selectOrderInfoList(OrderInfo orderInfo) {
        return orderInfoMapper.selectOrderInfoList(orderInfo);
    }

    @Override
    public OrderInfo selectOrderInfoById(Long id) {
        OrderInfo orderInfo = orderInfoMapper.selectById(id);
        if (orderInfo != null) {
            List<OrderItem> orderItemList = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, id));
            orderInfo.setOrderItemList(orderItemList);
        }
        return orderInfo;
    }

    @Override
    public TradeVo trade() {
        TradeVo tradeVo = new TradeVo();
        // 获取选中的购物车商品
        R<List<CartInfo>> cartResult = remoteCartService.getAllChecked(SecurityConstants.INNER);
        if (cartResult == null || R.isError(cartResult)) {
            throw new ServiceException("获取购物车商品失败");
        }
        List<CartInfo> cartInfoList = cartResult.getData();

        List<OrderItem> orderItemList = new ArrayList<>();
        if (!CollectionUtils.isEmpty(cartInfoList)) {
            // 计算总金额
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (CartInfo cartInfo : cartInfoList) {
                OrderItem orderItem = buildOrderItem(cartInfo);
                orderItemList.add(orderItem);
                totalAmount = totalAmount.add(cartInfo.getSkuPrice().multiply(new BigDecimal(cartInfo.getSkuNum())));
            }
            tradeVo.setTotalAmount(totalAmount);
            tradeVo.setOrderItemList(orderItemList);
        } else {
            tradeVo.setTotalAmount(BigDecimal.ZERO);
            tradeVo.setOrderItemList(new ArrayList<>());
        }

        // 生成交易号，30分钟过期
        String tradeNo = UUID.randomUUID().toString().replace("-", "");
        Long userId = SecurityUtils.getUserId();
        saveCheckoutSnapshot(userId, tradeNo, orderItemList, true);
        tradeVo.setTradeNo(tradeNo);

        return tradeVo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitOrder(OrderForm orderForm) {
        Long userId = SecurityUtils.getUserId();

        Object storedSnapshot = redisTemplate.opsForValue().get(tradeKey(userId));
        if (!(storedSnapshot instanceof CheckoutSnapshot snapshot)
                || !Objects.equals(snapshot.getTradeNo(), orderForm.getTradeNo())) {
            throw new ServiceException("交易号不匹配或已过期");
        }
        List<OrderItem> orderItemList = snapshot.getOrderItemList();
        if (CollectionUtils.isEmpty(orderItemList)) {
            throw new ServiceException("结算商品为空");
        }

        // 获取用户地址
        R<UserAddress> addressResult = remoteUserAddressService.getUserAddress(orderForm.getUserAddressId(), SecurityConstants.INNER);
        if (addressResult == null || R.isError(addressResult)) {
            throw new ServiceException("获取收货地址失败");
        }
        UserAddress userAddress = addressResult.getData();
        if (userAddress == null) {
            throw new ServiceException("收货地址不存在");
        }
        if (!userId.equals(userAddress.getUserId())) {
            throw new ServiceException("无权使用该收货地址");
        }

        // 创建订单
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setUserId(userId);
        // 修复：订单号使用时间戳+用户ID+随机数，避免高并发碰撞
        orderInfo.setOrderNo(System.currentTimeMillis() + "" + userId + (int)(Math.random() * 10000));
        orderInfo.setOrderStatus(0); // 待付款
        orderInfo.setReceiverName(userAddress.getName());
        orderInfo.setReceiverPhone(userAddress.getPhone());
        orderInfo.setReceiverTagName(userAddress.getTagName());
        orderInfo.setReceiverProvince(userAddress.getProvinceCode());
        orderInfo.setReceiverCity(userAddress.getCityCode());
        orderInfo.setReceiverDistrict(userAddress.getDistrictCode());
        orderInfo.setReceiverAddress(userAddress.getAddress());

        // 计算总金额
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItem orderItem : orderItemList) {
            totalAmount = totalAmount.add(orderItem.getSkuPrice().multiply(new BigDecimal(orderItem.getSkuNum())));
        }

        orderInfo.setTotalAmount(totalAmount);
        orderInfo.setOriginalTotalAmount(totalAmount);
        orderInfo.setFeightFee(orderForm.getFeightFee() != null ? orderForm.getFeightFee() : BigDecimal.ZERO);

        // 保存订单
        orderInfoMapper.insert(orderInfo);

        // 保存订单项
        for (OrderItem orderItem : orderItemList) {
            orderItem.setOrderId(orderInfo.getId());
            orderItemMapper.insert(orderItem);
        }

        // 记录订单日志
        OrderLog orderLog = new OrderLog();
        orderLog.setOrderId(orderInfo.getId());
        orderLog.setOperateUser("用户");
        orderLog.setProcessStatus(0);
        orderLog.setNote("用户提交订单");
        orderLogMapper.insert(orderLog);

        if (snapshot.isCartCheckout()) {
            R<Void> clearResult = remoteCartService.clearCheckedCart(SecurityConstants.INNER);
            if (clearResult == null || R.isError(clearResult)) {
                throw new ServiceException("清理购物车失败");
            }
        }

        // 发送延迟消息，30分钟后检查订单是否支付
        try {
            rabbitService.sendDelayMessage(MqConst.EXCHANGE_CANCEL_ORDER, MqConst.ROUTING_CANCEL_ORDER, orderInfo.getId(), 30 * 60 * 1000);
        } catch (Exception e) {
            log.error("发送延迟消息失败", e);
        }

        redisTemplate.delete(tradeKey(userId));
        return orderInfo.getId();
    }

    @Override
    public OrderInfo getByOrderId(String orderId) {
        OrderInfo orderInfo = null;
        // 先尝试按数字ID查询
        try {
            Long id = Long.parseLong(orderId);
            orderInfo = orderInfoMapper.selectById(id);
        } catch (NumberFormatException e) {
            // 不是数字，按orderNo查询
            LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(OrderInfo::getOrderNo, orderId);
            orderInfo = orderInfoMapper.selectOne(wrapper);
        }
        if (orderInfo != null) {
            List<OrderItem> orderItemList = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderInfo.getId()));
            orderInfo.setOrderItemList(orderItemList);
        }
        return orderInfo;
    }

    @Override
    public List<OrderInfo> selectUserOrderInfoList(Long userId, Integer orderStatus) {
        return orderInfoMapper.selectUserOrderInfoList(userId, orderStatus);
    }

    @Override
    public void cancelOrder(Long orderId) {
        OrderInfo orderInfo = orderInfoMapper.selectById(orderId);
        if (orderInfo == null) {
            throw new ServiceException("订单不存在");
        }
        // 归属校验：只能取消自己的订单
        if (!SecurityUtils.getUserId().equals(orderInfo.getUserId())) {
            throw new ServiceException("无权操作该订单");
        }
        cancelPendingOrder(orderId, "用户", "用户取消订单");
    }

    @Override
    public void cancelUnpaidOrder(Long orderId) {
        cancelPendingOrder(orderId, "系统", "订单超时自动取消");
    }

    @Override
    public TradeVo buy(Long skuId) {
        TradeVo tradeVo = new TradeVo();

        // 获取商品SKU信息
        R<ProductSku> skuResult = remoteProductService.getProductSku(skuId, SecurityConstants.INNER);
        if (skuResult == null || R.isError(skuResult)) {
            throw new ServiceException("获取商品信息失败");
        }
        ProductSku productSku = skuResult.getData();

        List<OrderItem> orderItemList = new ArrayList<>();
        if (productSku != null) {
            OrderItem orderItem = new OrderItem();
            orderItem.setSkuId(skuId);
            orderItem.setSkuName(productSku.getSkuName());
            orderItem.setThumbImg(productSku.getThumbImg());
            orderItem.setSkuPrice(productSku.getSalePrice());
            orderItem.setSkuNum(1);
            orderItemList.add(orderItem);
            tradeVo.setTotalAmount(productSku.getSalePrice());
        } else {
            throw new ServiceException("商品不存在");
        }
        tradeVo.setOrderItemList(orderItemList);

        // 生成交易号，存入Redis，30分钟过期
        String tradeNo = UUID.randomUUID().toString().replace("-", "");
        Long userId = SecurityUtils.getUserId();
        saveCheckoutSnapshot(userId, tradeNo, orderItemList, false);
        tradeVo.setTradeNo(tradeNo);

        return tradeVo;
    }

    @Override
    public void updateOrderStatus(String orderNo, Integer orderStatus) {
        if (!Integer.valueOf(1).equals(orderStatus)) {
            throw new ServiceException("仅允许将订单更新为已支付状态");
        }

        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
        if (orderInfo == null) {
            throw new ServiceException("订单不存在");
        }
        if (Integer.valueOf(1).equals(orderInfo.getOrderStatus())) {
            return;
        }
        if (!Integer.valueOf(0).equals(orderInfo.getOrderStatus())) {
            throw new ServiceException("订单当前状态不允许支付");
        }

        OrderInfo update = new OrderInfo();
        update.setOrderStatus(1);
        update.setPaymentTime(new Date());
        int rows = orderInfoMapper.update(update, new LambdaUpdateWrapper<OrderInfo>()
                .eq(OrderInfo::getId, orderInfo.getId())
                .eq(OrderInfo::getOrderStatus, 0));
        if (rows == 0) {
            OrderInfo latestOrder = orderInfoMapper.selectById(orderInfo.getId());
            if (latestOrder != null && Integer.valueOf(1).equals(latestOrder.getOrderStatus())) {
                return;
            }
            throw new ServiceException("订单当前状态不允许支付");
        }

        OrderLog orderLog = new OrderLog();
        orderLog.setOrderId(orderInfo.getId());
        orderLog.setOperateUser("系统");
        orderLog.setProcessStatus(1);
        orderLog.setNote("支付成功");
        orderLogMapper.insert(orderLog);
    }

    private void saveCheckoutSnapshot(Long userId, String tradeNo, List<OrderItem> orderItemList, boolean cartCheckout) {
        CheckoutSnapshot snapshot = new CheckoutSnapshot();
        snapshot.setTradeNo(tradeNo);
        snapshot.setOrderItemList(orderItemList);
        snapshot.setCartCheckout(cartCheckout);
        redisTemplate.opsForValue().set(tradeKey(userId), snapshot, 30, TimeUnit.MINUTES);
    }

    private String tradeKey(Long userId) {
        return TRADE_KEY_PREFIX + userId;
    }

    private OrderItem buildOrderItem(CartInfo cartInfo) {
        OrderItem orderItem = new OrderItem();
        orderItem.setSkuId(cartInfo.getSkuId());
        orderItem.setSkuName(cartInfo.getSkuName());
        orderItem.setThumbImg(cartInfo.getThumbImg());
        orderItem.setSkuPrice(cartInfo.getSkuPrice());
        orderItem.setSkuNum(cartInfo.getSkuNum());
        return orderItem;
    }

    private void cancelPendingOrder(Long orderId, String operateUser, String note) {
        OrderInfo update = new OrderInfo();
        update.setOrderStatus(-1);
        int rows = orderInfoMapper.update(update, new LambdaUpdateWrapper<OrderInfo>()
                .eq(OrderInfo::getId, orderId)
                .eq(OrderInfo::getOrderStatus, 0));
        if (rows == 0) {
            return;
        }

        OrderLog orderLog = new OrderLog();
        orderLog.setOrderId(orderId);
        orderLog.setOperateUser(operateUser);
        orderLog.setProcessStatus(-1);
        orderLog.setNote(note);
        orderLogMapper.insert(orderLog);
    }
}
