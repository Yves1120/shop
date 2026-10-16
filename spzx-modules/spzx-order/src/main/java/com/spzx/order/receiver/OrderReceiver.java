package com.spzx.order.receiver;

import com.rabbitmq.client.Channel;
import com.spzx.common.rabbit.constant.MqConst;
import com.spzx.order.service.IOrderInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class OrderReceiver {

    @Autowired
    private IOrderInfoService orderInfoService;

    /**
     * 订单超时自动取消
     * 监听延迟消息，30分钟后检查订单状态，未支付则自动取消
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_CANCEL_ORDER, durable = "true"),
            exchange = @Exchange(value = MqConst.EXCHANGE_CANCEL_ORDER, delayed = "true"),
            key = {MqConst.ROUTING_CANCEL_ORDER}
    ))
    public void cancelOrderListener(Long orderId, Message message, Channel channel) throws IOException {
        log.info("收到订单超时取消消息，订单ID：{}", orderId);
        try {
            if (orderId != null) {
                orderInfoService.cancelUnpaidOrder(orderId);
                log.info("订单{}超时检查完成", orderId);
            }
            // 手动确认消息
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("订单超时取消处理异常，订单ID：{}", orderId, e);
            // 拒绝消息，不重新入队，避免无限重试
            channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
        }
    }
}
