# Voxy Vulkan 接口 v1

适用于 Minecraft 26.3 Fabric 原生 Vulkan。Voxy 将 WynnVitrail 设为可选依赖，确认模组存在后再加载引用以下类型的适配类。接口位于 `dev.vitrail.voxy.VoxyBridge`。可运行的最小 GPU 提供方见 `fabric/src/sodiumProbe/java/dev/vitrail/render/VoxyGpuProbe.java`。

```java
var registration = VoxyBridge.register(VoxyBridge.VERSION, new VoxyBridge.Provider() {
    public boolean enabled() { return lodRenderer.enabled(); }
    public VoxyBridge.Camera begin(VoxyBridge.Frame frame) {
        return lodRenderer.prepareCamera(frame);
    }
    public GpuTextureView draw(VoxyBridge.Draw draw) {
        return lodRenderer.record(draw);
    }
    public void invalidate() { lodRenderer.releaseFrameResources(); }
});
// Voxy 卸载时，在渲染线程执行 registration.close()。
```

这里的 `lodRenderer` 由 Voxy 实现。26.3 的视图类型为 `com.mojang.renderpearl.api.textures.GpuTextureView`。

## 生命周期和阶段

- 注册一个提供方。注册、注销和启用变化触发下一次主视图的光影包重载；仅在当前维度的补丁可用时定义 `VOXY=3`。`voxy.json.version` 为 1。
- 主相机每帧调用一次 `begin`。返回本帧 model-view、供光影包使用的正向 OpenGL 裁剪空间投影、近远平面距离和以区块计的距离。Voxy 自行转换用于 Vulkan 栅格化的投影。返回 null 表示当帧跳过。WynnVitrail 持有矩阵副本，每帧更新逆矩阵和历史矩阵。首次进入或重置时历史等于本帧。
- 两次 `draw` 均在主相机不透明近景结束后，先 OPAQUE 后 TRANSLUCENT，均早于 deferred。Solas 的 deferred1 在此时已经需要半透明水面颜色和深度。每次回调进入时没有活动的 render pass，可以通过 `draw.device().createCommandEncoder()` 录制 compute、复制和绘制，在返回前关闭自己打开的 pass，由游戏提交。无需自行提交队列。
- `Frame.width/height` 是渲染尺寸，`displayWidth/displayHeight` 是窗口像素尺寸；各轴渲染比例由两者相除。`load` 标识本次光影包加载，`serial` 标识这一加载内的帧。重载、离开世界及尺寸变化会作废之前的视图。提供方在 `invalidate` 清除缓存，并遵循 RenderPearl 的 GPU 资源延迟释放规则。

## 附件、采样与补丁

- `colors` 按片元输出槽排列，已经选好当前可写的 colortex 正反面。Solas 的两组依次为 [0,3] 和 [0,1,7]。每个颜色附件应以 LOAD 打开。半透明管线用 `patch.translucentBlend(slot)` 取四个混合因子或 off；空值沿用提供方原状态。
- `nearDepth` 为主相机的原始反向 Z，Vulkan 窗口范围 0..1，远端清零，较大值更近。比较前将 LOD 的深度转换到同一投影。`patch.separateDepth()` 为 true 时只读取该附件。若有 `coverage`，在可见 LOD 像素写主相机窗口深度，使 WynnVitrail 后续补画原版场景时保留这些像素。
- 每个阶段返回提供方持有的、单采样的正向窗口深度纹理，近 0 远 1，未覆盖处为 1。第二份应含不透明和半透明 LOD；第一份保持不透明快照。各自必须一直可采样到本帧最后的 composite。提供方负责在写入后结束 pass，使用游戏设备管理 layout 和访问同步。借用的 `Draw` 纹理限当次回调使用；避免同时采样正在写的同一子资源。
- `draw.samplerNames()` 按配置顺序返回名称。`GraphicsApi.blockAndSamplers(blockName, draw.samplerNames())` 可创建描述符布局，块在首位，后面是按顺序排列的组合图像采样器。按相同名称绑定 `draw.sampler().apply(name)`。Solas 使用场景深度、阴影、噪声、gaux 和远景深度。远景半透明深度在生成之前回退到不透明深度，两者都未生成时采样远平面。
- `Frame.uniforms()` 含标准值及当前包的自定义表达式。提供方从最终 GLSL 收集块成员，用 `frame.uniformBlock(members)` 建 std140 布局，在绘制阶段调用 `block.write(sink, frame.camera())`。可通过 `block.unanswered()` 检查缺少的数据。按 `frame.material(blockState)` 设置片元的 customId，材质缓存以 `materialGeneration()` 与 load 标识。
- `patch.opaqueSource()/translucentSource()` 已展开 include 并应用包选项。按 `shaderDefines()` 设置编译环境。提供方定义与 Voxy 一致的 `VoxyFragmentParameters`（sampledColour、tile、uv、face、modelId、lightMap、tinting、customId），拼接并调用 `voxy_emitFragment`，负责最终 Vulkan 着色器编译及顶点输入。将 `taaOffset()` 作为抖动函数体，使用当前渲染视口和相同帧序号，在 LOD 顶点阶段施加一次。空函数体按零偏移处理。
- 当前实现以 Solas 3.9 为验收包。未实现的字段包括 ssbos、renderScale、useViewportDims、skipShaderDepthHackFix、useDynamicFarPlane。遇到这些字段会记录包名、维度和字段并关闭该包的 Voxy 扩展。

## 验证命令

```powershell
./gradlew.bat build :fabric:sodiumMixinProbe -PvoxyProbe "-PsolasVoxyPack=<Solas 3.9 解压目录>"
```

原文件测试覆盖三个维度。无窗口 GPU 探针用固定三角形验证 MRT、独立混合、近景遮挡、两份远景深度回读以及两种尺寸下的重新注册。真实 LOD 的剔除、材质和水面接缝需要 Voxy Vulkan 提供方接入后进游戏联测。
