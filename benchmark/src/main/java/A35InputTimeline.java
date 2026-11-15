import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
/** Shell-UID input only. No app instrumentation, screenshot, frame observer, network or service. */
public final class A35InputTimeline {
  static final class Point {long ms;int action,x,y;String name;}
  public static void main(String[] args) throws Exception {
    Class<?> type=Class.forName("android.hardware.input.InputManagerGlobal");
    Object manager=type.getMethod("getInstance").invoke(null);
    Method inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
    if(args.length==1&&args[0].equals("--capabilities")){System.out.println("InputManagerGlobal.injectInputEvent available; shell UID; no events injected");return;}
    long preroll=300;for(int a=1;a<args.length;a++){if(args[a].startsWith("preroll="))preroll=Long.parseLong(args[a].substring(8));else throw new IllegalArgumentException("Unknown option");}
    if(args.length<1||preroll<0||preroll>2000)throw new IllegalArgumentException("One validated timeline CSV path required");
    ArrayList<Point> points=new ArrayList<>();long previous=-1;
    try(BufferedReader reader=new BufferedReader(new FileReader(args[0]))){for(String row;(row=reader.readLine())!=null;){String[] p=row.split(",");if(p.length!=4)throw new IllegalArgumentException("Malformed CSV");Point e=new Point();e.ms=Long.parseLong(p[0]);e.name=p[1];e.action=e.name.equals("DOWN")?0:e.name.equals("UP")?1:e.name.equals("MOVE")?2:e.name.equals("CANCEL")?3:-1;e.x=Integer.parseInt(p[2]);e.y=Integer.parseInt(p[3]);if(e.ms<previous||e.ms>30000||e.action<0||e.x<0||e.x>1080||e.y<0||e.y>2340)throw new IllegalArgumentException("Unsafe event");previous=e.ms;points.add(e);}}
    if(points.isEmpty()||points.get(0).action!=0||(points.get(points.size()-1).action!=1&&points.get(points.size()-1).action!=3))throw new IllegalArgumentException("A complete DOWN/UP or DOWN/CANCEL gesture is required");
    MotionEvent warm=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0f,0f,0);warm.recycle();
    for(int w=0;w<200000;w++){System.nanoTime();SystemClock.uptimeNanos();}
    try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);}catch(Throwable ignored){}
    /* Pre-roll: busy CPU plus read-only InputManager binder calls (no events injected) so clocks and binder paths are warm before DOWN. */
    Method ids=type.getMethod("getInputDeviceIds");long prerollEnd=System.nanoTime()+preroll*1000000L;int binderCalls=0;while(System.nanoTime()<prerollEnd){if((binderCalls&63)==0)ids.invoke(manager);binderCalls++;}
    System.out.println("PREROLL,"+preroll+","+binderCalls);
    StringBuilder log=new StringBuilder(points.size()*220);
    /* Schedule clocks are captured together; event times are stamped from the schedule (uptime base + planned ms), not from send time. */
    long startMono=System.nanoTime()+100000000L,startUptimeNs=SystemClock.uptimeNanos()+100000000L,startElapsed=SystemClock.elapsedRealtimeNanos()+100000000L,startWall=System.currentTimeMillis()*1000000L+100000000L,downTime=0;
    System.out.println("START,"+startWall+","+startMono+","+startElapsed+","+startUptimeNs);
    for(int i=0;i<points.size();i++){Point e=points.get(i);long target=startMono+e.ms*1000000L;
      /* pure busy-wait (gestures are <=~1 s): no sleep overshoot, CPU stays at speed */
      while(System.nanoTime()<target){}
      long before=System.nanoTime(),elapsed=SystemClock.elapsedRealtimeNanos(),wall=System.currentTimeMillis()*1000000L,actualUptime=SystemClock.uptimeMillis();
      long eventTime=(startUptimeNs+e.ms*1000000L)/1000000L;if(e.action==0)downTime=eventTime;
      MotionEvent event=MotionEvent.obtain(downTime,eventTime,e.action,e.x,e.y,e.action==1||e.action==3?0f:1f,1f,0,1f,1f,0,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      /* mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC: enqueue without waiting for app dispatch; dispatcher queue keeps order */
      boolean accepted=(Boolean)inject.invoke(manager,event,0);event.recycle();long after=System.nanoTime();
      log.append("EVENT,").append(i).append(",").append(e.name).append(",").append(e.x).append(",").append(e.y).append(",").append(e.ms).append(",").append(wall).append(",").append(wall+(after-before)).append(",").append(before).append(",").append(elapsed).append(",").append(accepted).append(",").append(eventTime).append(",").append(downTime).append(",async,").append(after-before).append(",").append(actualUptime).append("\n");
      if(!accepted)throw new IllegalStateException("Input injection rejected at event "+i);
    }
    System.out.print(log.toString());
    System.out.println("END,"+(System.currentTimeMillis()*1000000L)+","+System.nanoTime()+","+SystemClock.elapsedRealtimeNanos());
  }
}
