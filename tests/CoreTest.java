import io.github.qqmodulemanager.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CoreTest {
    private static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static final class Action {
        final String id; final AtomicInteger clicks=new AtomicInteger();
        Action(String id){this.id=id;}
        void click(){clicks.incrementAndGet();}
    }
    static final class Candidate implements ProviderSelector.Candidate {
        boolean ready=true,failed,initialized,dirty,success=true;int attempts;
        public boolean ready(){return ready;}
        public boolean failed(){return failed;}
        public boolean initialized(){return initialized;}
        public boolean initialize(){attempts++;initialized=success;return success;}
        public void recordFailure(Throwable t){failed=true;}
        public boolean dirtyOnFailure(){return dirty;}
    }
    public static void main(String[]args)throws Exception {
        Action copy=new Action("qq:copy"), qa=new Action("qauxv:copy"), qfun=new Action("qfun:copy"), fresh=new Action("qq:new");
        List<Action> original=Collections.unmodifiableList(List.of(copy,qa,qfun,fresh));
        List<Action> filtered=MenuOrder.apply(original,a->a.id,List.of("qfun:copy","qq:copy"),Set.of("qauxv:copy"));
        check(filtered.equals(List.of(qfun,copy,fresh)),"hide only the namespaced action; append unknowns");
        check(original.size()==4,"do not mutate original collection");
        filtered.get(0).click();check(qfun.clicks.get()==1 && copy.clicks.get()==0,"reorder keeps the original click object");
        check(MenuOrder.apply(original,a->a.id,List.of(),Set.of()).equals(original),"reset preserves incoming order");
        check(MenuOrder.apply(original,a->a.id,List.of(),Set.of("qq:copy","qauxv:copy","qfun:copy","qq:new")).isEmpty(),"all hidden is supported");
        Action duplicate=new Action("qq:copy");
        check(MenuOrder.apply(List.of(copy,duplicate),a->a.id,List.of(),Set.of("qq:copy")).size()==2,"ambiguous identities remain visible");
        for(String chat:List.of("group","private"))for(String kind:List.of("text","image","voice","video","file")) {
            check(MenuOrder.apply(original,a->a.id,List.of("qfun:copy"),Set.of()).get(0)==qfun,chat+"/"+kind);
        }
        Map<String,Candidate> map=new HashMap<>();Candidate primary=new Candidate(),secondary=new Candidate(),third=new Candidate();
        map.put("qa",primary);map.put("qfun",secondary);map.put("ono",third);
        List<String> order=List.of("qa","qfun","ono");Set<String> participants=Set.copyOf(order);
        check(ProviderSelector.select(order,participants,map::get).owner.equals("qa"),"highest ready provider selected");
        check(primary.attempts==1 && secondary.attempts==0 && third.attempts==0,"only winner initialized");
        ProviderSelector.select(order,participants,map::get);check(primary.attempts==1,"initialized provider not initialized twice");
        primary.failed=true;
        check(ProviderSelector.select(order,participants,map::get).owner.equals("qfun"),"explicit failure selects next");
        secondary.failed=true;third.success=false;
        check(ProviderSelector.select(order,participants,map::get).phase.equals("unavailable"),"all failures disable the feature");
        primary=new Candidate();primary.ready=false;map.put("qa",primary);
        check(ProviderSelector.select(order,participants,map::get).phase.equals("waiting"),"not ready is not a failure");
        map.remove("qa");check(ProviderSelector.select(order,participants,map::get).phase.equals("waiting"),"late registration cannot activate a lower provider");
        primary=new Candidate();primary.dirty=true;primary.success=false;secondary=new Candidate();map.put("qa",primary);map.put("qfun",secondary);
        check(ProviderSelector.select(order,participants,map::get).phase.equals("restart_required"),"partial native initialization requires restart");
        check(secondary.attempts==0,"no backup while native modifications may remain");
        check(ProviderSelector.select(order,participants,map::get).owner.equals("qfun"),"quarantined failure is skipped on next boot");
        FeatureGate gate=new FeatureGate();gate.switchTo("qa");Object outer=gate.enter();Object inner=gate.enter();
        check(gate.owner().equals("qa"),"nested invocation snapshot");gate.leave(inner);gate.leave(outer);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean switched=new AtomicBoolean();AtomicReference<Throwable> threadFailure=new AtomicReference<>();
        Thread call=new Thread(()->{Object token=gate.enter();entered.countDown();try{release.await();check(gate.owner().equals("qa"),"same owner in before/after callbacks");}catch(Throwable e){threadFailure.set(e);}finally{gate.leave(token);}});
        call.start();entered.await();Thread change=new Thread(()->{gate.switchTo("qfun");switched.set(true);});change.start();
        check(!switched.get(),"switch waits for in-flight invocation");release.countDown();call.join();change.join();
        check(switched.get() && gate.owner().equals("qfun"),"next invocation uses new owner");
        check(threadFailure.get()==null,"invocation thread completed without assertion failure");
        System.out.println(checks+" core checks passed");
    }
}
