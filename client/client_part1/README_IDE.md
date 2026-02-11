# IDE / Lint 说明（client_part1）

如果你在 Cursor / VS Code 里看到很多红色报错（包名不匹配、无法解析 import 等），**代码本身没问题**，Maven 可以正常编译（`mvn compile` 已通过）。问题出在 **Java 语言服务 / 项目识别** 上。

## 常见报错

- `The declared package "client_part1" does not match the expected package "client_part1.src.main.java.client_part1"`
- `The import com.fasterxml cannot be resolved` / `org.java_websocket cannot be resolved`
- `ObjectMapper cannot be resolved to a type` / `WebSocketClient cannot be resolved to a type`

## 处理步骤（任选其一或组合使用）

### 1. 清理 Java 语言服务工作区（优先尝试）

1. 按 `Ctrl+Shift+P`（Mac: `Cmd+Shift+P`）打开命令面板  
2. 输入并执行：**Java: Clean Java Language Server Workspace**  
3. 在弹窗中选择 **Restart and delete**，等待重启后重新打开项目  

这样会强制重新分析项目结构和 Maven 配置。

### 2. 用 Maven 重新加载项目

1. 打开左侧 **Maven** 视图（或 `Ctrl+Shift+P` → “Maven: Focus on Maven View”）  
2. 找到 **client_part1** 项目  
3. 点击刷新 / **Reload**（或右键 → Update Project）  

确保依赖（Java-WebSocket、Jackson）被正确下载并加入 classpath。

### 3. 以 client_part1 为工作区根目录打开（最稳定）

如果当前是打开整个 `assignment 1` 文件夹：

1. **文件 → 打开文件夹**  
2. 选择 **client_part1** 目录（即包含 `pom.xml` 和 `src` 的那一层）  
3. 用这个文件夹作为当前工作区  

这样 Java 会把 `src/main/java` 识别为源码根目录，包名 `client_part1` 就会和路径一致，上述包名不匹配和 import 错误通常会消失。

### 4. 确认扩展与 Java 版本

- 已安装 **Extension Pack for Java**（或至少 **Language Support for Java** + **Maven for Java**）  
- 已安装 **JDK 17** 并在 VS Code 里选择为 Java 运行环境（`Ctrl+Shift+P` → “Java: Configure Java Runtime”）

---

完成上述步骤后，一般只需 **清理 Java 语言服务工作区** 或 **以 client_part1 为根目录打开** 即可消除这些 lint 报错。
