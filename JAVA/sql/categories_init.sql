-- ============================================
-- ShopAi 商品分类初始化数据
-- ============================================

-- ── 顶级分类 ──
INSERT INTO categories (id, parent_id, name, sort_order) VALUES
(1,  0, '男装',     1),
(2,  0, '女装',     2),
(3,  0, '鞋靴',     3),
(4,  0, '配饰',     4),
(5,  0, '运动户外', 5);

-- ── 男装子分类 ──
INSERT INTO categories (id, parent_id, name, sort_order) VALUES
(11, 1, 'T恤',      1),
(12, 1, '衬衫',     2),
(13, 1, '卫衣',     3),
(14, 1, '外套',     4),
(15, 1, '裤装',     5),
(16, 1, '西装',     6);

-- ── 女装子分类 ──
INSERT INTO categories (id, parent_id, name, sort_order) VALUES
(21, 2, '连衣裙',   1),
(22, 2, 'T恤/衬衫', 2),
(23, 2, '卫衣',     3),
(24, 2, '外套',     4),
(25, 2, '裤装',     5),
(26, 2, '半身裙',   6);

-- ── 鞋靴子分类 ──
INSERT INTO categories (id, parent_id, name, sort_order) VALUES
(31, 3, '运动鞋',   1),
(32, 3, '休闲鞋',   2),
(33, 3, '凉鞋/拖鞋', 3),
(34, 3, '靴子',     4),
(35, 3, '正装鞋',   5);

-- ── 配饰子分类 ──
INSERT INTO categories (id, parent_id, name, sort_order) VALUES
(41, 4, '帽子',     1),
(42, 4, '围巾',     2),
(43, 4, '腰带',     3),
(44, 4, '袜子',     4),
(45, 4, '太阳镜',   5),
(46, 4, '包包',     6);

-- ── 运动户外子分类 ──
INSERT INTO categories (id, parent_id, name, sort_order) VALUES
(51, 5, '运动上衣', 1),
(52, 5, '运动裤',   2),
(53, 5, '瑜伽服',   3),
(54, 5, '冲锋衣',   4),
(55, 5, '运动内衣', 5);
