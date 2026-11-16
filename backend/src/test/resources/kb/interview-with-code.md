# 2026-08-20 用友（中）面试题｜电话初筛未通过（推测）

## 1. `VARCHAR` 和 `CHAR` 的区别

### 简短回答

`CHAR` 是定长字符串，`VARCHAR` 是变长字符串。`CHAR` 适合长度固定的数据，`VARCHAR` 适合长度变化较大的数据。

### 详细解析

| 对比项 | CHAR | VARCHAR |
| --- | --- | --- |
| 长度 | 固定长度 | 可变长度 |
| 存储 | 通常按定义长度分配 | 按实际内容长度加长度信息存储 |
| 性能 | 长度固定时读写更稳定 | 节省空间，适合大多数业务字段 |
| 适用场景 | 状态码、国家代码、固定格式编号 | 姓名、地址、标题、描述 |

例如手机号、国家代码等长度固定的字段可考虑 `CHAR`；文章标题、用户名等长度变化较大的字段通常使用 `VARCHAR`。

## 2. 有没有使用过自定义注解配合 AOP 记录日志？

### 参考回答

使用过。通常会定义一个自定义注解，例如 `@OperationLog`，标记在需要记录操作日志的 Controller 或 Service 方法上；然后定义一个切面，通过 `@Around` 或 `@AfterReturning` 拦截这些方法，统一记录操作人、操作模块、请求参数、执行结果、耗时和异常信息。这样可以把日志记录从业务代码中抽离出来。

项目中的普通程序日志可以使用 Lombok 的 `@Slf4j`，但它只是自动生成 `log` 对象；而自定义注解加 AOP 主要用于统一记录用户操作日志、审计日志或接口访问日志。

### 实现思路

1. 定义注解，配置操作名称、模块等元数据。
2. 使用 `@Aspect` 定义切面，并通过 `@Pointcut("@annotation(...)" )` 匹配注解方法。
3. 在环绕通知中记录开始时间，执行目标方法。
4. 根据执行结果记录成功日志；捕获异常后记录失败日志并继续抛出异常。
5. 计算接口耗时，结合当前登录用户和请求 IP 保存操作日志。

### 示例

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {
    String module();
    String action();
}
```

```java
@Aspect
@Component
public class OperationLogAspect {
    @Around("@annotation(operationLog)")
    public Object record(ProceedingJoinPoint point,
                         OperationLog operationLog) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = point.proceed();
            long cost = System.currentTimeMillis() - start;
            // 保存模块、动作、用户、参数、结果和耗时
            return result;
        } catch (Throwable ex) {
            // 保存失败状态和异常信息
            throw ex;
        }
    }
}
```

使用时只需要标注方法：

```java
@OperationLog(module = "用户管理", action = "创建用户")
public void createUser(CreateUserRequest request) {
    // 业务代码
}
```

### 面试重点

要区分两种“日志”：`@Slf4j` 是便捷使用日志框架记录程序运行日志；自定义注解 + AOP 是把操作日志、审计日志等横切逻辑统一处理。记录参数时还要注意脱敏，避免密码、Token 等敏感信息写入日志。

## 3. 讲解一下 AOP 和 IoC

### IoC

IoC 是控制反转，原本由对象自己负责创建和管理依赖，变成由 Spring 容器负责创建、装配和管理对象。开发者只需要声明依赖，例如使用构造器注入。

```java
@Service
public class OrderService {
    private final UserRepository userRepository;

    public OrderService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
}
```

### AOP

AOP 是面向切面编程，用于把日志、事务、权限、监控等横切逻辑从业务代码中抽离出来，在指定连接点统一执行。

Spring AOP 基于动态代理实现：有接口时通常使用 JDK 动态代理，没有接口时通常使用 CGLIB。常见注解包括 `@Aspect`、`@Pointcut`、`@Before`、`@After`、`@Around` 和 `@AfterReturning`。

### 两者关系

IoC 解决对象创建和依赖管理问题，AOP 解决横切逻辑复用问题。二者都是 Spring 的核心能力。

## 4. MyBatis 中 `#` 和 `$` 的区别

### 简短回答

`#{}` 使用预编译参数，可以防止 SQL 注入；`${}` 是字符串直接拼接，存在 SQL 注入风险，只适合拼接经过严格白名单校验的表名、列名或排序字段。

### 示例

```xml
<!-- 推荐：预编译参数 -->
SELECT * FROM user WHERE id = #{id}

<!-- 风险：字符串拼接 -->
SELECT * FROM user ORDER BY ${column}
```

`#{id}` 最终会转换为 `?` 占位符并通过参数绑定传入。`${column}` 会直接替换成文本，因此不能直接接收用户输入。

## 5. Java 数组常用方法，以及 List 的常用方法

### 数组

Java 数组长度固定，常见操作通过 `java.util.Arrays` 完成：

```java
int[] numbers = {3, 1, 2};
Arrays.sort(numbers);                 // 排序
int index = Arrays.binarySearch(numbers, 2); // 二分查找，要求有序
int[] copy = Arrays.copyOf(numbers, 5);      // 复制并扩容
boolean same = Arrays.equals(numbers, copy); // 比较内容
String text = Arrays.toString(numbers);      // 转为字符串
```

### List 常用方法

```java
List<String> list = new ArrayList<>();
list.add("Java");             // 添加元素
list.add(0, "Spring");         // 指定位置添加
list.get(0);                   // 获取元素
list.set(0, "Spring Boot");    // 修改元素
list.remove("Java");          // 按对象删除
list.remove(0);                // 按索引删除
list.contains("Java");        // 判断是否包含
list.size();                   // 获取大小
list.isEmpty();                // 判断是否为空
list.clear();                  // 清空
```

### 面试易错点

- `ArrayList` 底层是动态数组，随机访问快，中间插入和删除较慢。
- `LinkedList` 底层是双向链表，按索引访问慢，头尾操作相对方便。
- `remove(1)` 对 `List<Integer>` 通常表示按索引删除，不是删除值为 1 的元素。
- 遍历时直接修改集合可能触发 `ConcurrentModificationException`，应使用迭代器的 `remove()` 或收集结果后再修改。

## 深入回答补充

### AOP 日志完整落地
自定义操作日志通常包含注解、切面、日志实体和异步落库四部分。切面在目标方法前后采集用户、请求 URI、参数摘要、结果、耗时和异常；敏感参数脱敏，日志写入可通过消息队列异步化。若日志必须与业务结果一致，可使用事务事件或 Outbox；若只用于审计，需保证失败重试和最终可查。

### Spring 与 MyBatis 易错点
IoC 重点回答对象创建、依赖注入和生命周期；AOP 重点回答代理、连接点、通知和自调用失效。`#{}` 是预编译参数，`${}` 是文本替换，动态表名或排序字段必须白名单校验。
