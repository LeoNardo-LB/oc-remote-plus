# HTML / PDF 文件查看器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 FileViewer 支持 HTML（WebView 渲染）和 PDF（PDF.js 内嵌渲染）文件查看。

**Architecture:** 扩展 FileType 枚举新增 HTML/PDF；HTML 复用 RenderWebView 直接渲染（文本类型，支持源码↔渲染切换）；PDF 为二进制类型，集成 PDF.js 核心 + 轻量 wrapper 到 assets，新建 PdfViewer Composable 渲染到 canvas。

**Tech Stack:** Kotlin + Jetpack Compose + Android WebView + PDF.js (legacy build) + JUnit4/MockK

**Spec:** `docs/superpowers/specs/2026-07-08-html-pdf-viewer-design.md`

## Global Constraints

- JDK 21, Kotlin, Compose BOM 2026.05.01
- minSdk 26（WebView Chromium 支持 Web Worker）
- 使用 Material 3 原生组件和配色
- 测试命令：`.\gradlew :app:testDevDebugUnitTest --rerun`（180s 超时）
- 编译检查：`.\gradlew :app:compileDevDebugKotlin`（120s 超时）
- 不自动 commit/push，除非用户明确要求
- 编码前用 context7 查询库 API，禁止臆测

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `FileType.kt` | 修改 | 新增 HTML、PDF 枚举值 + EXT_MAP + supportsSourceView |
| `FileViewerViewModel.kt` | 修改 | loadLive() BINARY 新增 PDF + toggleRenderMode 防御 |
| `RenderWebView.kt` | 修改 | when 新增 HTML 分支 |
| `FileViewerScreen.kt` | 修改 | when 新增 HTML/PDF + TopBar 按钮条件 |
| `PdfViewer.kt` | **新建** | PDF.js WebView 封装 + JS Interface + 工具栏 |
| `assets/pdfjs/pdf.min.js` | **新建** | PDF.js 核心库（legacy build） |
| `assets/pdfjs/pdf.worker.min.js` | **新建** | PDF.js Worker |
| `assets/pdfjs/pdf_viewer.html` | **新建** | 轻量 wrapper 页面 |
| `assets/pdfjs/viewer.css` | **新建** | 移动端适配样式 |
| `FileTypeTest.kt` | 修改 | HTML/PDF 用例 |
| `FileViewerViewModelTest.kt` | 修改 | HTML/PDF 用例 |
| `strings.xml` | 修改 | PDF 相关字符串 |

---

### Task 1: FileType 枚举扩展（HTML + PDF + supportsSourceView）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileType.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileTypeTest.kt`

**Interfaces:**
- Produces: `FileType.HTML`, `FileType.PDF`, `FileType.supportsSourceView` 属性

- [ ] **Step 1: Write failing tests (FileTypeTest.kt)**

在 `FileTypeTest.kt` 末尾（`}` 之前）新增：

```kotlin
    @Test
    fun `html extension maps to HTML`() {
        assertEquals(FileType.HTML, FileType.fromExtension("index.html"))
    }
    @Test
    fun `htm extension maps to HTML`() {
        assertEquals(FileType.HTML, FileType.fromExtension("page.htm"))
    }
    @Test
    fun `uppercase HTML maps to HTML`() {
        assertEquals(FileType.HTML, FileType.fromExtension("page.HTML"))
    }
    @Test
    fun `pdf extension maps to PDF`() {
        assertEquals(FileType.PDF, FileType.fromExtension("report.pdf"))
    }
    @Test
    fun `supportsRender is true for HTML and PDF`() {
        assertTrue(FileType.HTML.supportsRender)
        assertTrue(FileType.PDF.supportsRender)
    }
    @Test
    fun `supportsSourceView is false for PDF and true for all others`() {
        assertFalse(FileType.PDF.supportsSourceView)
        assertTrue(FileType.TEXT.supportsSourceView)
        assertTrue(FileType.HTML.supportsSourceView)
        assertTrue(FileType.MARKDOWN.supportsSourceView)
        assertTrue(FileType.IMAGE.supportsSourceView)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.FileTypeTest" --rerun`
Expected: 编译失败（`HTML`、`PDF`、`supportsSourceView` 未定义）

- [ ] **Step 3: Implement FileType changes**

将 `FileType.kt` 完整替换为：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.viewer

enum class FileType {
    TEXT,
    MARKDOWN,
    IMAGE,
    SVG,
    CSV,
    JSON,
    HTML,
    PDF;

    val supportsRender: Boolean get() = this != TEXT

    /** PDF 的源码模式对 base64 数据无意义，不显示切换按钮 */
    val supportsSourceView: Boolean get() = this != PDF

    companion object {
        private val EXT_MAP: Map<String, FileType> = mapOf(
            "md" to MARKDOWN, "markdown" to MARKDOWN, "mdx" to MARKDOWN,
            "png" to IMAGE, "jpg" to IMAGE, "jpeg" to IMAGE,
            "gif" to IMAGE, "webp" to IMAGE, "bmp" to IMAGE,
            "svg" to SVG,
            "csv" to CSV, "tsv" to CSV,
            "json" to JSON,
            "html" to HTML, "htm" to HTML,
            "pdf" to PDF
        )

        fun fromExtension(filePath: String): FileType =
            EXT_MAP[filePath.substringAfterLast('.', "").lowercase()] ?: TEXT
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.FileTypeTest" --rerun`
Expected: PASS（全部 17 个测试通过）

- [ ] **Step 5: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileType.kt app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileTypeTest.kt
git commit -m "feat: add HTML and PDF to FileType enum with supportsSourceView"
```

---

### Task 2: HTML 渲染（RenderWebView + FileViewerScreen）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/RenderWebView.kt:42-92`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerScreen.kt:156-211`

**Interfaces:**
- Consumes: `FileType.HTML`（Task 1）
- Produces: `RenderWebView` 支持 `fileType = FileType.HTML` 时渲染原始 HTML

**Note:** HTML 是文本类型，走现有 `loadLive()` 文本流程，ViewModel 无需修改。`defaultRenderMode` 对 HTML 返回 SOURCE（因为 `supportsRender=true`），和 CSV/SVG 一致。

- [ ] **Step 1: Modify RenderWebView.kt — 新增 HTML 分支**

在 `RenderWebView.kt` 的 `html` remember 块（第 42-48 行）中，`when (fileType)` 新增 HTML 分支。

找到现有的 `when` 代码：
```kotlin
    val html = remember(content, fileType, mimeType, bgColorArgb) {
        when (fileType) {
            FileType.IMAGE -> buildImageHtml(content, mimeType, bgHex)
            FileType.SVG, FileType.CSV -> RenderHtmlBuilder.build(fileType, content, isDark, bgHex, fgHex)
            else -> ""
        }
    }
```

替换为（新增 HTML 分支，HTML 直接用 content 作为完整 HTML document）：
```kotlin
    val html = remember(content, fileType, mimeType, bgColorArgb) {
        when (fileType) {
            FileType.IMAGE -> buildImageHtml(content, mimeType, bgHex)
            FileType.SVG, FileType.CSV -> RenderHtmlBuilder.build(fileType, content, isDark, bgHex, fgHex)
            FileType.HTML -> content   // 原始 HTML 直接加载
            else -> ""
        }
    }
```

然后在 `factory` 块（第 56-83 行）中，`settings.apply` 新增 HTML 配置。

找到现有的 settings 块：
```kotlin
            WebView(ctx).apply {
                settings.apply {
                    if (fileType == FileType.MARKDOWN) {
                        javaScriptEnabled = true
                    }
                    if (fileType == FileType.IMAGE) {
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
```

替换为：
```kotlin
            WebView(ctx).apply {
                settings.apply {
                    if (fileType == FileType.MARKDOWN || fileType == FileType.HTML) {
                        javaScriptEnabled = true
                    }
                    if (fileType == FileType.IMAGE) {
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    if (fileType == FileType.HTML) {
                        // 安全限制：禁止访问本地文件系统
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = true   // 某些 HTML 需要 localStorage
                        saveFormData = false
                    }
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
```

然后在 `loadUrl` / `loadDataWithBaseURL` 逻辑（第 77-81 行）中，新增 HTML 分支。

找到：
```kotlin
                if (fileType == FileType.MARKDOWN) {
                    loadUrl("file:///android_asset/markdown_viewer.html")
                } else {
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
```

替换为：
```kotlin
                if (fileType == FileType.MARKDOWN) {
                    loadUrl("file:///android_asset/markdown_viewer.html")
                } else {
                    // HTML/SVG/CSV/IMAGE 都用 loadDataWithBaseURL
                    // baseURL=null：相对路径不解析到本地文件系统
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
```

（此行无需改动，HTML 走 else 分支已正确。确认无需修改此段。）

- [ ] **Step 2: Modify FileViewerScreen.kt — 新增 HTML 渲染分支**

在 `FileViewerScreen.kt` 第 195-209 行的 `when (uiState.fileType)` 块中新增 HTML 分支。

找到：
```kotlin
                        if (showRender && uiState.fileType.supportsRender) {
                            when (uiState.fileType) {
                                FileType.MARKDOWN -> RenderWebView(
                                    content = uiState.content,
                                    fileType = FileType.MARKDOWN,
                                    visible = true
                                )
                                FileType.IMAGE, FileType.SVG, FileType.CSV -> RenderWebView(
                                    content = uiState.content,
                                    fileType = uiState.fileType,
                                    mimeType = uiState.mimeType ?: "image/*",
                                    visible = true
                                )
                                else -> {} // no-op
                            }
                        }
```

替换为：
```kotlin
                        if (showRender && uiState.fileType.supportsRender) {
                            when (uiState.fileType) {
                                FileType.MARKDOWN -> RenderWebView(
                                    content = uiState.content,
                                    fileType = FileType.MARKDOWN,
                                    visible = true
                                )
                                FileType.IMAGE, FileType.SVG, FileType.CSV -> RenderWebView(
                                    content = uiState.content,
                                    fileType = uiState.fileType,
                                    mimeType = uiState.mimeType ?: "image/*",
                                    visible = true
                                )
                                FileType.HTML -> RenderWebView(
                                    content = uiState.content,
                                    fileType = FileType.HTML,
                                    visible = true
                                )
                                else -> {} // no-op (PDF handled in Task 6)
                            }
                        }
```

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/RenderWebView.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerScreen.kt
git commit -m "feat: add HTML rendering support in RenderWebView and FileViewerScreen"
```

---

### Task 3: PDF ViewModel 分支 + toggleRenderMode 防御

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerViewModel.kt:67-73, 201-210`
- Test: `app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerViewModelTest.kt`

**Interfaces:**
- Consumes: `FileType.PDF`、`FileType.supportsSourceView`（Task 1）
- Produces: PDF binary 文件加载到 `uiState.content`（base64），`renderMode = RENDER_PREVIEW`

- [ ] **Step 1: Write failing tests (FileViewerViewModelTest.kt)**

在 `FileViewerViewModelTest.kt` 末尾（`}` 之前）新增：

```kotlin
    @Test
    fun `init with html file sets fileType HTML and renderMode SOURCE`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocremotev2.domain.model.FileContent(
                path = "index.html",
                type = ContentType.TEXT,
                content = "<!DOCTYPE html><html><body><h1>Hello</h1></body></html>"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "index.html"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.fileType == FileType.HTML) { "fileType should be HTML" }
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) { "HTML should default to SOURCE" }
    }

    @Test
    fun `toggleRenderMode works for HTML files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocremotev2.domain.model.FileContent(
                path = "index.html",
                type = ContentType.TEXT,
                content = "<!DOCTYPE html><html></html>"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "index.html"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) { "HTML defaults to SOURCE" }
        vm.toggleRenderMode()
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) { "toggle to RENDER_PREVIEW" }
    }

    @Test
    fun `init with pdf binary sets fileType PDF and retains base64 content`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocremotev2.domain.model.FileContent(
                path = "report.pdf",
                type = ContentType.BINARY,
                content = "JVBERi0xLjcKJeLjz9MK",
                mimeType = "application/pdf"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "report.pdf"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.fileType == FileType.PDF) { "fileType should be PDF" }
        assert(!vm.uiState.value.isBinary) { "PDF should not be marked isBinary" }
        assert(vm.uiState.value.content == "JVBERi0xLjcKJeLjz9MK") { "base64 content should be retained" }
        assert(vm.uiState.value.mimeType == "application/pdf") { "mimeType should be preserved" }
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) { "PDF should default to RENDER_PREVIEW" }
    }

    @Test
    fun `toggleRenderMode is no-op for PDF files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocremotev2.domain.model.FileContent(
                path = "report.pdf",
                type = ContentType.BINARY,
                content = "JVBERi0xLjcKJeLjz9MK",
                mimeType = "application/pdf"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "report.pdf"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        val modeBefore = vm.uiState.value.renderMode
        vm.toggleRenderMode()
        assert(vm.uiState.value.renderMode == modeBefore) { "PDF toggle should be no-op" }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.FileViewerViewModelTest.*pdf*" --tests "*.FileViewerViewModelTest.*html*" --rerun`
Expected: PDF 测试失败（PDF 走 isBinary=true 分支）；HTML toggle 测试可能失败（如果 supportsSourceView 未生效）

- [ ] **Step 3: Modify loadLive() BINARY 分支 — 新增 PDF case**

在 `FileViewerViewModel.kt` 第 67-73 行，找到现有的 BINARY 处理：

```kotlin
                    if (c.type == ContentType.BINARY) {
                        val ft = FileType.fromExtension(filePath)
                        if (ft == FileType.IMAGE) {
                            _uiState.update { it.copy(isLoading = false, isBinary = false, fileType = ft, content = c.content, mimeType = c.mimeType, renderMode = FileViewerRenderMode.RENDER_PREVIEW) }
                        } else {
                            _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                        }
                    }
```

替换为（新增 PDF when 分支）：

```kotlin
                    if (c.type == ContentType.BINARY) {
                        val ft = FileType.fromExtension(filePath)
                        when (ft) {
                            FileType.IMAGE -> {
                                _uiState.update { it.copy(isLoading = false, isBinary = false, fileType = ft, content = c.content, mimeType = c.mimeType, renderMode = FileViewerRenderMode.RENDER_PREVIEW) }
                            }
                            FileType.PDF -> {
                                _uiState.update { it.copy(isLoading = false, isBinary = false, fileType = ft, content = c.content, mimeType = c.mimeType, renderMode = FileViewerRenderMode.RENDER_PREVIEW) }
                            }
                            else -> {
                                _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                            }
                        }
                    }
```

- [ ] **Step 4: Modify toggleRenderMode() — 新增 supportsSourceView 检查**

在 `FileViewerViewModel.kt` 第 201-210 行，找到：

```kotlin
    fun toggleRenderMode() {
        val current = _uiState.value
        if (!current.fileType.supportsRender || current.mode == FileViewerMode.DIFF) return
        _uiState.update {
            it.copy(
                renderMode = if (it.renderMode == FileViewerRenderMode.SOURCE) FileViewerRenderMode.RENDER_PREVIEW
                else FileViewerRenderMode.SOURCE
            )
        }
    }
```

替换为（新增 `!current.fileType.supportsSourceView` 条件）：

```kotlin
    fun toggleRenderMode() {
        val current = _uiState.value
        if (!current.fileType.supportsRender || !current.fileType.supportsSourceView || current.mode == FileViewerMode.DIFF) return
        _uiState.update {
            it.copy(
                renderMode = if (it.renderMode == FileViewerRenderMode.SOURCE) FileViewerRenderMode.RENDER_PREVIEW
                else FileViewerRenderMode.SOURCE
            )
        }
    }
```

- [ ] **Step 5: Run tests to verify pass**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.FileViewerViewModelTest" --rerun`
Expected: PASS（全部测试通过，包括新增的 4 个 HTML/PDF 测试）

- [ ] **Step 6: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerViewModel.kt app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerViewModelTest.kt
git commit -m "feat: add PDF binary loading and toggleRenderMode supportsSourceView guard"
```

---

### Task 4: PDF.js Assets 集成

**Files:**
- Create: `app/src/main/assets/pdfjs/pdf.min.js`
- Create: `app/src/main/assets/pdfjs/pdf.worker.min.js`
- Create: `app/src/main/assets/pdfjs/pdf_viewer.html`
- Create: `app/src/main/assets/pdfjs/viewer.css`

**Interfaces:**
- Produces: `assets/pdfjs/pdf_viewer.html` 页面，接受 `loadPdfFromBase64(base64)` JS 调用

**PDF.js 版本说明：**
- 使用 PDF.js v4.x legacy build（兼容 Android WebView）
- Legacy build 生成 `pdf.min.js` + `pdf.worker.min.js`（非 ESM 模块，适合直接 `<script src>` 引用）
- 从 jsdelivr CDN 下载

- [ ] **Step 1: 创建 assets/pdfjs/ 目录**

Run:
```powershell
New-Item -ItemType Directory -Path "app\src\main\assets\pdfjs" -Force
```
Expected: 目录创建成功

- [ ] **Step 2: 下载 PDF.js legacy build 文件**

Run（PowerShell，从 jsdelivr CDN 下载 v4.0.379 legacy build）:
```powershell
$base = "https://cdn.jsdelivr.net/npm/pdfjs-dist@4.0.379/legacy/build"
Invoke-WebRequest -Uri "$base/pdf.min.js" -OutFile "app\src\main\assets\pdfjs\pdf.min.js"
Invoke-WebRequest -Uri "$base/pdf.worker.min.js" -OutFile "app\src\main\assets\pdfjs\pdf.worker.min.js"
```

验证文件下载成功：
```powershell
Get-ChildItem app\src\main\assets\pdfjs\ | Select-Object Name, Length
```
Expected: `pdf.min.js` (~350KB) 和 `pdf.worker.min.js` (~700KB)

**注意：** 如果代理阻止了 CDN 访问，在命令前设置 `$env:HTTPS_PROXY = "http://127.0.0.1:7897"`。如果 CDN 版本不可用，可降级到 `pdfjs-dist@3.11.174` legacy build。

- [ ] **Step 3: 创建 viewer.css**

Write to `app/src/main/assets/pdfjs/viewer.css`:

```css
* { margin: 0; padding: 0; box-sizing: border-box; }

html, body {
    width: 100%;
    min-height: 100%;
    background: #ffffff;
    -webkit-user-select: none;
    user-select: none;
}

#pdf-canvas {
    display: block;
    margin: 0 auto;
    max-width: 100%;
    height: auto;
}

#loading-indicator {
    position: fixed;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    font-family: sans-serif;
    font-size: 16px;
    color: #666;
}

#error-message {
    position: fixed;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    font-family: sans-serif;
    font-size: 14px;
    color: #d32f2f;
    text-align: center;
    max-width: 80%;
}
```

- [ ] **Step 4: 创建 pdf_viewer.html**

Write to `app/src/main/assets/pdfjs/pdf_viewer.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
    <link rel="stylesheet" href="viewer.css">
    <script src="pdf.min.js"></script>
</head>
<body>
    <div id="loading-indicator">Loading PDF...</div>
    <canvas id="pdf-canvas" style="display:none;"></canvas>
    <div id="error-message" style="display:none;"></div>

    <script>
        var pdfDoc = null;
        var pageNum = 1;
        var pageRendering = false;
        var pageNumPending = null;
        var scale = 1.5;

        /**
         * Load PDF from base64-encoded data.
         * Called from Kotlin via evaluateJavascript.
         */
        async function loadPdfFromBase64(base64Data) {
            try {
                var uint8 = Uint8Array.from(atob(base64Data), function(c) { return c.charCodeAt(0); });
                pdfjsLib.GlobalWorkerOptions.workerSrc = 'pdf.worker.min.js';

                var loadingTask = pdfjsLib.getDocument({ data: uint8 });
                pdfDoc = await loadingTask.promise;

                document.getElementById('loading-indicator').style.display = 'none';
                document.getElementById('pdf-canvas').style.display = 'block';

                pageNum = 1;
                renderPage(pageNum);
                PdfViewerInterface.onPdfLoaded(pdfDoc.numPages);
            } catch (err) {
                document.getElementById('loading-indicator').style.display = 'none';
                var errEl = document.getElementById('error-message');
                errEl.style.display = 'block';
                errEl.textContent = 'Failed to load PDF: ' + (err.message || 'Unknown error');
                PdfViewerInterface.onError(err.message || 'Unknown error');
            }
        }

        /**
         * Render a specific page to canvas.
         */
        function renderPage(num) {
            pageRendering = true;
            pdfDoc.getPage(num).then(function(page) {
                var viewport = page.getViewport({ scale: scale });
                var outputScale = window.devicePixelRatio || 1;

                var canvas = document.getElementById('pdf-canvas');
                var context = canvas.getContext('2d');

                canvas.width = Math.floor(viewport.width * outputScale);
                canvas.height = Math.floor(viewport.height * outputScale);
                canvas.style.width = Math.floor(viewport.width) + 'px';
                canvas.style.height = Math.floor(viewport.height) + 'px';

                var transform = outputScale !== 1
                    ? [outputScale, 0, 0, outputScale, 0, 0]
                    : null;

                var renderContext = {
                    canvasContext: context,
                    transform: transform,
                    viewport: viewport
                };

                var renderTask = page.render(renderContext);
                renderTask.promise.then(function() {
                    pageRendering = false;
                    if (pageNumPending !== null) {
                        renderPage(pageNumPending);
                        pageNumPending = null;
                    }
                    PdfViewerInterface.onPageRendered(pageNum, pdfDoc.numPages);
                });
            });
        }

        function queueRenderPage(num) {
            if (pageRendering) {
                pageNumPending = num;
            } else {
                renderPage(num);
            }
        }

        function nextPage() {
            if (pageNum >= pdfDoc.numPages) return;
            pageNum++;
            queueRenderPage(pageNum);
        }

        function prevPage() {
            if (pageNum <= 1) return;
            pageNum--;
            queueRenderPage(pageNum);
        }

        function setScale(newScale) {
            scale = newScale;
            queueRenderPage(pageNum);
        }
    </script>
</body>
</html>
```

- [ ] **Step 5: 验证 assets 结构**

Run:
```powershell
Get-ChildItem -Recurse app\src\main\assets\pdfjs\ | Select-Object Name, Length
```
Expected: 4 个文件（pdf.min.js, pdf.worker.min.js, pdf_viewer.html, viewer.css）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/pdfjs/
git commit -m "feat: add PDF.js legacy build and custom viewer wrapper to assets"
```

---

### Task 5: PdfViewer Composable

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/PdfViewer.kt`

**Interfaces:**
- Consumes: `assets/pdfjs/pdf_viewer.html`（Task 4）、`FileType.PDF`（Task 1）
- Produces: `PdfViewer(base64Data: String, visible: Boolean, modifier: Modifier)` Composable

- [ ] **Step 1: 创建 PdfViewer.kt**

Write to `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/PdfViewer.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.viewer

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocremotev2.ui.theme.SpacingTokens

/**
 * PDF viewer using PDF.js in WebView.
 * Loads base64-encoded PDF data and renders pages to canvas.
 *
 * @param base64Data Base64-encoded PDF content from API
 * @param visible Whether the viewer is visible
 * @param modifier Compose modifier
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PdfViewer(
    base64Data: String,
    visible: Boolean = true,
    modifier: Modifier = Modifier
) {
    var totalPages by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(1) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    val escapedBase64 = remember(base64Data) {
        base64Data.replace("\\", "\\\\").replace("'", "\\'")
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = true          // 需要加载 assets 中的 pdf.js
                        allowContentAccess = false
                        builtInZoomControls = true
                        displayZoomControls = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }

                    // JS Interface for callbacks from pdf_viewer.html
                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun onPdfLoaded(total: Int) {
                                totalPages = total
                                isLoading = false
                            }

                            @android.webkit.JavascriptInterface
                            fun onPageRendered(current: Int, total: Int) {
                                currentPage = current
                                totalPages = total
                            }

                            @android.webkit.JavascriptInterface
                            fun onError(message: String) {
                                isLoading = false
                                hasError = true
                            }
                        },
                        "PdfViewerInterface"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(
                                "loadPdfFromBase64('$escapedBase64')",
                                null
                            )
                        }
                    }

                    webChromeClient = WebChromeClient()
                    loadUrl("file:///android_asset/pdfjs/pdf_viewer.html")
                }
            },
            update = { webView ->
                webView.visibility = if (visible) View.VISIBLE else View.GONE
            }
        )

        // ── Toolbar overlay (page navigation) ──
        if (!isLoading && !hasError && totalPages > 0) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            // 通过 evaluateJavascript 调用 JS 翻页
                        },
                        enabled = currentPage > 1
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous page"
                        )
                    }
                    Text(
                        text = "$currentPage / $totalPages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp)
                    )
                    IconButton(
                        onClick = {
                            // 通过 evaluateJavascript 调用 JS 翻页
                        },
                        enabled = currentPage < totalPages
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next page"
                        )
                    }
                }
            }
        }

        // ── Loading indicator ──
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
```

**注意：** 上面的翻页按钮的 `onClick` 是空的占位。需要在 `factory` 中保存 WebView 引用，才能在 onClick 中调用 `evaluateJavascript`。下一步修正。

- [ ] **Step 2: 修正翻页按钮 — 保存 WebView 引用**

将 Step 1 中的 PdfViewer.kt 中翻页按钮的 onClick 替换为实际调用。需要在 Composable 作用域内保存 WebView 引用：

在 `Box(modifier.fillMaxSize())` 前面添加：
```kotlin
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
```

在 `factory` 的 `WebView(ctx).apply {` 内最后，`loadUrl` 之后不需要额外操作，但需要在 `update` 块中保存引用：

将 `update` 块替换为：
```kotlin
            update = { webView ->
                webView.visibility = if (visible) View.VISIBLE else View.GONE
                webViewRef = webView
            }
```

将上一页 IconButton 的 onClick 替换为：
```kotlin
                    IconButton(
                        onClick = {
                            webViewRef?.evaluateJavascript("prevPage()", null)
                        },
                        enabled = currentPage > 1
                    ) {
```

将下一页 IconButton 的 onClick 替换为：
```kotlin
                    IconButton(
                        onClick = {
                            webViewRef?.evaluateJavascript("nextPage()", null)
                        },
                        enabled = currentPage < totalPages
                    ) {
```

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/PdfViewer.kt
git commit -m "feat: add PdfViewer Composable with PDF.js WebView integration"
```

---

### Task 6: FileViewerScreen PDF 集成 + TopBar 调整

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerScreen.kt:195-211, 335`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerScreen.kt:157-158`（showRender 条件）

**Interfaces:**
- Consumes: `PdfViewer`（Task 5）、`FileType.supportsSourceView`（Task 1）

- [ ] **Step 1: 在 FileViewerScreen when 块中新增 PDF 分支**

在 Task 2 中已修改的 `when (uiState.fileType)` 块（FileViewerScreen.kt 第 196 行附近），找到 `else -> {} // no-op (PDF handled in Task 6)` 行，替换为：

```kotlin
                                FileType.PDF -> PdfViewer(
                                    base64Data = uiState.content,
                                    visible = true
                                )
                                else -> {} // no-op
```

- [ ] **Step 2: 修改 TopBar 切换按钮条件**

在 `FileViewerScreen.kt` 的 `FileViewerTopBar` composable 中（第 335 行附近），找到切换按钮的条件：

```kotlin
            if (annotationCount == 0 && uiState.fileType.supportsRender && uiState.mode != FileViewerMode.DIFF) {
```

替换为（新增 `supportsSourceView` 检查）：

```kotlin
            if (annotationCount == 0 && uiState.fileType.supportsRender && uiState.fileType.supportsSourceView && uiState.mode != FileViewerMode.DIFF) {
```

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run all viewer tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.FileViewerViewModelTest" --tests "*.FileTypeTest" --rerun`
Expected: PASS（全部测试通过）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/viewer/FileViewerScreen.kt
git commit -m "feat: integrate PdfViewer and hide toggle button for PDF in FileViewerScreen"
```

---

### Task 7: 字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

**Note:** PdfViewer 当前硬编码了英文字符串（Loading / Previous page / Next page）。如需多语言，在此 Task 添加字符串资源并引用。如果项目暂不需要，可跳过此 Task。

- [ ] **Step 1: 检查现有 viewer 相关字符串**

Run:
```powershell
Select-String -Path app\src\main\res\values\strings.xml -Pattern "viewer_|pdf_"
```
Expected: 看到现有 viewer_ 字符串

- [ ] **Step 2: 添加 PDF 相关字符串（可选）**

在 `strings.xml` 中 `<resources>` 块内合适位置添加：

```xml
    <!-- PDF Viewer -->
    <string name="pdf_loading">Loading PDF…</string>
    <string name="pdf_error_load">Failed to load PDF</string>
    <string name="pdf_previous_page">Previous page</string>
    <string name="pdf_next_page">Next page</string>
```

**注意：** 如果添加了字符串资源，需要同步更新 `PdfViewer.kt` 中的硬编码字符串为 `stringResource(R.string.xxx)`。如果暂不需要多语言支持，可跳过此 Task。

- [ ] **Step 3: Run lokit sync（如需多语言）**

```bash
lokit
```

- [ ] **Step 4: Commit（如有改动）**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: add PDF viewer string resources"
```

---

## Self-Review

### Spec Coverage

| Spec 要求 | 对应 Task |
|-----------|-----------|
| FileType 枚举扩展（HTML + PDF + supportsSourceView） | Task 1 ✅ |
| HTML 渲染（RenderWebView + JS enabled） | Task 2 ✅ |
| HTML 源码↔渲染切换 | Task 1 (FileType) + Task 2 (Screen) ✅ |
| PDF ViewModel loadLive BINARY 分支 | Task 3 ✅ |
| toggleRenderMode supportsSourceView 防御 | Task 3 ✅ |
| PDF.js assets 集成 | Task 4 ✅ |
| PdfViewer Composable | Task 5 ✅ |
| FileViewerScreen PDF 集成 | Task 6 ✅ |
| TopBar 按钮条件调整 | Task 6 ✅ |
| 字符串资源 | Task 7 ✅ |
| 单元测试（FileType + ViewModel） | Task 1 + Task 3 ✅ |

### Placeholder Scan

- Task 5 Step 2 翻页按钮 onClick 已从占位替换为实际 `evaluateJavascript` 调用 ✅
- 无 TBD/TODO ✅

### Type Consistency

- `FileType.HTML` / `FileType.PDF`：Task 1 定义，Task 2/3/5/6 使用 ✅
- `FileType.supportsSourceView`：Task 1 定义，Task 3 (toggleRenderMode) + Task 6 (TopBar) 使用 ✅
- `PdfViewer(base64Data, visible, modifier)`：Task 5 定义，Task 6 使用 ✅
- `loadPdfFromBase64(base64)` JS 函数：Task 4 定义，Task 5 调用 ✅
- `PdfViewerInterface` JS Interface：Task 4 HTML 中引用，Task 5 Kotlin 中实现 ✅
