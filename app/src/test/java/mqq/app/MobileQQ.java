package mqq.app;
public class MobileQQ extends android.app.Application {
    private static MobileQQ instance;
    private AppRuntime runtime;
    public MobileQQ(AppRuntime value){runtime=value;instance=this;}
    public static MobileQQ getMobileQQ(){return instance;}
    public AppRuntime peekAppRuntime(){return runtime;}
}
