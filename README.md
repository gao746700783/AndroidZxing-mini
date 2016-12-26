# AndroidZxing-mini
基于Androidzxing的二维码扫描AS版(精简版);zxing地址见:https://github.com/zxing/zxing


### 精简步骤

> 由于我们只需要二维码扫描的主要部分,而完整版的包含了许多无用的内容,
> 故此根据需要,重新封装和打包;

1. com.google.zxing.client.Android.Camera  基于Camera调用以及参数配置，核心包
2. DecodeFormatManager、DecodeThread、DecodeHandler 基于解码格式、解码线程、解码结果处理的解码类
3. ViewfinderView、ViewfinderResultPointCallBack 基于取景框视图定义的View类
4. CaptureActivity、CaptureActivityHandler 基于扫描Activity以及扫描结果处理的Capture类
5. InactivityTimer、BeepManager、FinishListener 基于休眠、声音、退出的辅助管理类
6. Intents、IntentSource、PrefrencesActivity 基于常量存储的常量类

首先,将zxing包下的android项目(eclipse版本)转为as项目,这个不必赘述;
其次,引入两个库项目作为module或直接添加依赖:
    // https://mvnrepository.com/artifact/com.google.zxing/android-core
    compile group: 'com.google.zxing', name: 'android-core', version: '3.3.0'
    // https://mvnrepository.com/artifact/com.google.zxing/core
    compile group: 'com.google.zxing', name: 'core', version: '3.3.0'
使用上述两个依赖项目的结果就是,出现了代码不一致的情况,有经验的开发者稍作修改即可;
最后,重新引入上述列出的几个包和包含的类等,重新运行即可;

### 存在问题

> 还存在一些问题,下面根据实际使用过程中出现的问题,逐一解决:
>
1. 二维码扫描的布局样式修改,与app整体的风格一致,问题不大;
2. 关于横竖屏的问题,在最新版本的代码中已做处理,可参考代码进行;
3. 关于5.0以前的权限问题,如果被手机管家等第三方的应用禁止掉相应的权限的问题,最新代码中已处理,可参考代码进行;
4. Android M版本及以后的权限处理;
5. 处理扫描后的解码结果,问题不大;
6. 的

###