package com.shopai.product;

import com.shopai.product.entity.ProductSku;
import com.shopai.product.feign.InventoryClient;
import com.shopai.product.service.ProductSkuServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class hcqTest {
    @Autowired
    private ProductSkuServiceImpl productService;
    @Autowired
    private InventoryClient inventoryClient;
    @Test
    public void test(){
        List<ProductSku> list = productService.list();
        for (ProductSku product : list) {
            inventoryClient.init(product.getId(),product.getStock());
        }
    }
}
