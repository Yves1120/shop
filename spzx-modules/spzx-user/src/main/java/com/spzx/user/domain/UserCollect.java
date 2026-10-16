package com.spzx.user.domain;

import com.spzx.common.core.web.domain.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户收藏对象 user_collect
 *
 * @author atguigu
 * @date 2024-09-27
 */
@Data
@Schema(description = "用户收藏")
public class UserCollect extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "SKU商品ID")
    private Long skuId;

}
