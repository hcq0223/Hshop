package com.shopai.order.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.order.entity.Address;
import com.shopai.order.service.AddressService;
import com.shopai.order.service.CustomerResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 地址管理控制器
 * RESTful 风格 API
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Controller.AddressController 迁移至 shopai-order 服务。
 * 鉴权改造：不再各自验签 JWT，userId 由网关 X-User-Id 头提供（CurrentUser）；
 * userId → customerId 映射改用 CustomerResolver（Feign 调用户服务客户内部接口，
 * 替代原 CustomerRefMapper 跨库直查 customers 表）。
 * <p>
 * 路径：/api/address/** 保持不变（见设计文档 5.4）。
 */
@RestController
@RequestMapping("/api/address")
@RequiredArgsConstructor
public class AddressController {
    private final AddressService addressService;
    private final CustomerResolver customerResolver;
    private static final Logger log = LoggerFactory.getLogger(AddressController.class);

    /**
     * 从请求头解析 userId，再映射为 customerId（内部 Feign 调用户服务客户内部接口）。
     */
    private Long resolveCustomerId(HttpServletRequest request) {
        return customerResolver.toCustomerId(CurrentUser.getUserId(request));
    }

    /**
     * 获取当前用户的所有地址（列表）
     * GET /api/address/list
     */
    @GetMapping("/list")
    public Result<?> list(HttpServletRequest request) {
        Long customerId = resolveCustomerId(request);
        List<Address> list = addressService.getAddressesByCustomerId(customerId);
        return Result.ok("地址列表", list);
    }

    /**
     * 根据ID查询单个地址
     * GET /api/address/{id}
     */
    @GetMapping("/{id}")
    public Result<?> getById(@PathVariable Long id,
                             HttpServletRequest request) {
        Address addr = addressService.getAddressById(id);
        if (addr == null) return Result.error("地址不存在");
        Long customerId = resolveCustomerId(request);
        if (!addr.getCustomerId().equals(customerId)) return Result.error("无权操作");
        return Result.ok("地址详情", addr);
    }

    /**
     * 新增地址
     * POST /api/address/add
     */
    @PostMapping("/add")
    public Result<?> add(@RequestBody Address address,
                         HttpServletRequest request) {
        Long customerId = resolveCustomerId(request);
        address.setCustomerId(customerId);
        addressService.addAddress(address);
        return Result.ok("地址添加成功");
    }

    /**
     * 修改地址
     * PUT /api/address/{id}
     */
    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id,
                            @RequestBody Address address,
                            HttpServletRequest request) {
        address.setId(id);
        Long customerId = resolveCustomerId(request);
        // 校验地址归属
        Address old = addressService.getAddressById(id);
        if (old == null) return Result.error("地址不存在");
        if (!old.getCustomerId().equals(customerId)) return Result.error("无权操作");
        // 保留原有 customerId
        address.setCustomerId(old.getCustomerId());
        if (address.getIsDefault() == null) address.setIsDefault(old.getIsDefault());
        addressService.updateAddress(address);
        return Result.ok("地址更新成功");
    }

    /**
     * 删除地址
     * DELETE /api/address/{id}
     */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id,
                            HttpServletRequest request) {
        Long customerId = resolveCustomerId(request);
        Address addr = addressService.getAddressById(id);
        if (addr == null) return Result.error("地址不存在");
        if (!addr.getCustomerId().equals(customerId)) return Result.error("无权操作");
        addressService.deleteAddress(id);
        return Result.ok("地址删除成功");
    }

    /**
     * 保留旧接口兼容（原路径）
     */
    @GetMapping("/addressList")
    public Result<?> getAddressesByCustomerId(HttpServletRequest request) {
        return list(request);
    }

    @GetMapping("/addressDetail")
    public Result<?> getAddressById(HttpServletRequest request) {
        Long customerId = resolveCustomerId(request);
        List<Address> list = addressService.getAddressesByCustomerId(customerId);
        return Result.ok("地址详情", list.isEmpty() ? null : list.get(0));
    }

    @PostMapping("/addAddress")
    public Result<?> addAddress(@RequestBody Address address,
                                HttpServletRequest request) {
        return add(address, request);
    }

    @PutMapping("/updateAddress")
    public Result<?> updateAddress(@RequestBody Address address,
                                   HttpServletRequest request) {
        return update(address.getId(), address, request);
    }

    @DeleteMapping("/deleteAddress")
    public Result<?> deleteAddress(HttpServletRequest request,
                                   @RequestBody Address address) {
        return delete(address.getId(), request);
    }

    @PutMapping("/setDefaultAddress")
    public Result<?> setDefaultAddress(HttpServletRequest request,
                                       @RequestBody Address address) {
        Long customerId = resolveCustomerId(request);
        addressService.setDefaultAddress(address.getId(), customerId);
        return Result.ok("默认地址设置成功");
    }
}
