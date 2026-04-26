# Android 刮刮乐实现说明

这个示例的关键不在于“把上层 View 隐藏”，而在于：

1. 先把银色涂层画到一张离屏 `Bitmap` 上。
2. 手指移动时，不是去改下层内容，而是用 `PorterDuff.Mode.CLEAR` 把这张涂层位图局部擦透明。
3. 每次 `dispatchDraw()` 先画真实中奖内容，再把“已经被擦掉部分带透明洞”的涂层位图盖上去。

这样就能得到非常细腻的“慢慢刮开”效果。

## 当前示例里最关键的文件

- `app/src/main/java/com/example/scratchoff/widget/ScratchCardLayout.kt`
- `app/src/main/java/com/example/scratchoff/MainActivity.kt`

## 细腻刮开的关键点

- 不是只在触摸点画一个圆，而是对两点之间做插值补点。
- `scratchBetween()` 会按固定步长在路径上连续盖很多个透明圆，避免手指滑快时出现断裂。
- 使用 `event.historySize` 补上 Android 系统缓存的历史触摸点，轨迹会更顺。
- 只局部 `invalidate(dirtyRect)`，避免全屏重绘导致掉帧。

## 迁移到你自己的项目时

- 把你的奖品内容作为 `ScratchCardLayout` 的子 View 放进去。
- 把 `setBrushSizeDp()` 调成你要的刮擦笔刷大小。
- 把 `setRevealThreshold()` 调成你要的自动揭开奖券比例。
- 如果你已经有自己的 UI，只保留 `ScratchCardLayout.kt` 也可以。

## 备注

这个工作区里没有本地 `gradle`，所以我没有在终端里直接跑构建；建议用 Android Studio 打开项目后同步依赖并生成 wrapper。
