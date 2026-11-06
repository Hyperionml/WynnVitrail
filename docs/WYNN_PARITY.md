# Wynncraft 适配核对（Minecraft 26.3，wynn7）

对照版本为 WynnIris 1.2.2（1.21.11，`5bd60bcaa`）。旧表以首次 Wynn 修改之前的 `87c124c09` 为基线，包含后来合入的 Iris 修改。wynn7 改用合并提交 `8f5ac079f` 的 Iris 父提交 `11f566b7f`，得到 148 个有差异的文件。下列命令可在 WynnIris 参考仓库重现清单。

```
git diff --name-status 11f566b7f 5bd60bcaa
git diff 11f566b7f 5bd60bcaa -- common/src/main/java
```

| 玩家可见的行为 | WynnIris 的实现入口 | 本次核对后的 26.3 实现 |
| --- | --- | --- |
| Fruma、TWP 等区域天空 | ItemStackStateLayerMixin、IrisRenderingPipeline、WynncraftSkyboxRenderer、EntityPatcher | 检测资源包标记并按高度选择主天空，处理最近天空、时间与淡入淡出。半透明绘制前使用场景深度和远景深度绘制七种程序天空。wynn6 对齐了 WynnIris 的同帧模式选择，成功画过天空就跳过帧末地形染色。wynn7 在旧天空的宽限期内持续丢弃对应实体，到期后解除。 |
| 动态 glint、物品染色和发光 | EntityPatcher、VanillaCoreTransformer、CommonUniforms、提交和顶点序列化 mixin | 以带部分刻的游戏时间驱动效果。将手持和光照标志与包自己的实体 ID 分开传递，支持提前染色、亮度、透明度限制、自发光和打包光照。 |
| 裂隙和半透明 VFX | IrisPipelines、ShaderSynthesizer、MixinItemRenderer_VfxTranslucency | 对缺少自身实体半透明程序的 Photon 单独分批。按实际颜色附件选择预乘或直通输出，独立处理透明度和光照贴图。 |
| 动作、假玩家、坐骑装甲 | EntityPatcher、WynncraftMountArmorOverlay、装备和头颅 mixin | 动作顶点与纹理坐标解码、近距离淡出、坐骑上的护甲与头饰、纹理遮罩、装备效果编号、透明像素裁切和资源重载失效。坐骑覆盖层遵循上游默认关闭设置。 |
| 雾、夜视和文字亮度 | WynncraftBiomeFogRenderer、MixinFogRenderer、MixinTextDisplayRenderer | Mist Woods 的起止距离、密度、生物群系雾色与太阳染色校正，实体与文字的夜视和亮度选项，文字背景独立限幅。 |
| 文字与过场 | ShaderSynthesizer、MixinTextDisplayRenderer、WynncraftTransitionRenderer | 识别服务器文本显示实体上的过场字符并在最终画面绘制 19 种过场。缺少字形程序时合成带动态颜色、运动与闪烁的字形程序。对于仅靠位图传递的过场，读取字形中心的 alpha 253 标记，在世界文字绘制时传递颜色与进度。 |
| 区域光影配置 | ambience、AmbienceRegionEditorScreen、IrisConfig、PipelineManager | 读取目录、旧 JSON 和归档，选择区域预设、管理配置和元数据、绘制区域、导入导出和依赖查找安装。临时区域切换和预热复用 Vitrail 的 Vulkan 编译缓存与颜色纹理池。 |
| 第三方兼容 | WynntilsCompat、DHCompat、VoxyLodDepth、VoxyEntityDepthClearPass | 阴影提交尊重 Wynntils 的实体隐藏状态。天空绘制使用 Vitrail 提供的远景深度。Voxy 的 Iris 接口返回 OpenGL 纹理和帧缓冲编号，当前 Vitrail 没有提供对应的 Vulkan 深度视图和附加颜色目标，Voxy 专用深度读取与清理尚未接入。 |

## wynn7 查出的行为差异

| 对照入口 | 在 26.3 补上的行为 |
| --- | --- |
| IrisRenderingPipeline、MixinFogRenderer | Mist Woods 离开区域后继续按 0.05 的逐帧系数画雾，直到淡出结束。雾色修正在离开边界时按当前颜色计算。 |
| ImmediateState、WynncraftTransitionRenderer | 每帧一起取出并清空过场的类型、颜色与进度。当前帧未绘制也会消费已取出的信号。 |
| Iris.loadExternalShaderpack、AmbienceRuntime | 区域预设独立于个人光影配置，在 Vitrail 中仍尊重用户显式强制的选项。加载实例保存当时的选项快照。切换有 1.5 秒冷却，失败时恢复上一套可用设置；上一套也不可用时回到基础包。失败的预设在 30 秒后可重试。 |
| AmbienceProfileSettingsScreen、AmbiencePackScreen、AmbienceWarmupScreen | 可单独编辑目标预设的光影选项、重置、保存或取消；管理页提供删除确认、目录入口和返回上级。预热显示进度、失败数并支持取消。 |
| IrisRenderingPipeline 的 ambiencePresentationMaskFrames | 在有效的同一世界、同一尺寸及格式下，以上一帧的世界画面遮盖切换首帧。HUD 在其后绘制。 |

## 验证边界

148 个文件是源码差异范围。发布脚本、模组标识、Iris 的 OpenGL 驱动接口和 Sodium 程序分组分别按目标加载器处理。区域预设文件格式和多边形判断可直接比较代码；光影选项界面使用 Vitrail 的选项模型，GPU 资源分配、远景深度、影子提交则需比较后端行为。WynnIris 的逐阶段诊断、缓存驻留与命中统计和编辑器交互细节未逐项复现。Voxy 的三个专用路径（读取 LOD 深度、实体遮挡清理、世界切换后重绑）仍缺少可用的 26.3 Vulkan 接口，通用远景深度不能代替这三项。

wynn7 的 26.3 构建通过 100 项自动测试（0 失败、0 跳过）。按上游编号核对了 32 种物品效果、7 种天空和 19 种过场；IrisVideoSettings 的 18 个 Wynn 配置键在目标端均有对应项。编号与配置键核对仅验证覆盖范围。自动测试覆盖区域预设隔离、过场信号消费、信号解码、时间、天空选择和淡出、着色器改写及全屏 uniform 布局。原生 shaderc 编译测试包括直接输出、延迟天空、Photon VFX、文字移动、文字背景以及全屏天空、场景、雾和过场阶段。此范围可以发现 GLSL 或 SPIR-V 编译错误。服务器资源包、光影包最终曝光和运行时提交顺序仍需要 Fruma 与 TWP 的实机画面确认。

WynnIris 的 OpenGL 程序二进制缓存、帧缓冲重绑、驱动清理和界面布局按 Vitrail 的 Vulkan 生命周期与 26.3 界面实现；预热速度和显存占用没有与 WynnIris 实机跑分。没有以全屏曝光补偿模拟两张截图之间的亮度差。

实现参考 [WynnIris 对照提交](https://github.com/clptvn/WynnIris/tree/5bd60bcaaf3693f8d919adc4a498dc9dace557a3)，移植的着色器算法、坐骑和区域配置实现沿用上游 LGPL-3.0 代码。项目许可证见 LICENSE。
