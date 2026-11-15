import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
/** Shell-UID multi-pointer input for #682 (derived from the A35 single-pointer driver). No app instrumentation.
 *  CSV rows: ms,action,id,x,y  with action in DOWN,PDOWN,MOVE,PUP,UP,CANCEL. MOVE rows sharing one ms merge into one event. */
public final class S20MultiTouch {
  static final class Row {long ms;String action;int id,x,y;}
  static final int W=1440,H=3200;
  public static void main(String[] args) throws Exception {
    Class<?> type;try{type=Class.forName("android.hardware.input.InputManagerGlobal");}catch(ClassNotFoundException e){type=Class.forName("android.hardware.input.InputManager");} /* API 33 fallback */
    Object manager=type.getMethod("getInstance").invoke(null);
    Method inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
    if(args.length<1)throw new IllegalArgumentException("timeline CSV path required");
    ArrayList<Row> rows=new ArrayList<>();long previous=-1;
    try(BufferedReader reader=new BufferedReader(new FileReader(args[0]))){for(String line;(line=reader.readLine())!=null;){if(line.isEmpty()||line.startsWith("#"))continue;String[] p=line.split(",");if(p.length!=5)throw new IllegalArgumentException("Malformed row: "+line);Row r=new Row();r.ms=Long.parseLong(p[0]);r.action=p[1];r.id=Integer.parseInt(p[2]);r.x=Integer.parseInt(p[3]);r.y=Integer.parseInt(p[4]);if(r.ms<previous||r.ms>60000||r.x<0||r.x>W||r.y<0||r.y>H||r.id<0||r.id>4)throw new IllegalArgumentException("Unsafe row: "+line);previous=r.ms;rows.add(r);}}
    // Build events: pointer state map id -> (x,y)
    ArrayList<Object[]> events=new ArrayList<>(); // {ms, action(int), ptrIds[], coords[][]}
    LinkedHashMap<Integer,int[]> ptrs=new LinkedHashMap<>();
    for(int i=0;i<rows.size();i++){Row r=rows.get(i);int action;
      if(r.action.equals("MOVE")){ptrs.put(r.id,new int[]{r.x,r.y});while(i+1<rows.size()&&rows.get(i+1).ms==r.ms&&rows.get(i+1).action.equals("MOVE")){i++;Row n=rows.get(i);ptrs.put(n.id,new int[]{n.x,n.y});}action=MotionEvent.ACTION_MOVE;}
      else if(r.action.equals("DOWN")){ptrs.clear();ptrs.put(r.id,new int[]{r.x,r.y});action=MotionEvent.ACTION_DOWN;}
      else if(r.action.equals("PDOWN")){ptrs.put(r.id,new int[]{r.x,r.y});action=MotionEvent.ACTION_POINTER_DOWN|(indexOf(ptrs,r.id)<<MotionEvent.ACTION_POINTER_INDEX_SHIFT);}
      else if(r.action.equals("PUP")){ptrs.put(r.id,new int[]{r.x,r.y});action=MotionEvent.ACTION_POINTER_UP|(indexOf(ptrs,r.id)<<MotionEvent.ACTION_POINTER_INDEX_SHIFT);}
      else if(r.action.equals("UP")){ptrs.put(r.id,new int[]{r.x,r.y});action=MotionEvent.ACTION_UP;}
      else if(r.action.equals("CANCEL")){action=MotionEvent.ACTION_CANCEL;}
      else throw new IllegalArgumentException("Unknown action "+r.action);
      int n=ptrs.size();MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[n];int k=0;
      for(Map.Entry<Integer,int[]> e:ptrs.entrySet()){props[k]=new MotionEvent.PointerProperties();props[k].id=e.getKey();props[k].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[k]=new MotionEvent.PointerCoords();coords[k].x=e.getValue()[0];coords[k].y=e.getValue()[1];coords[k].pressure=1f;coords[k].size=1f;k++;}
      events.add(new Object[]{r.ms,action,props,coords});
      if(r.action.equals("PUP"))ptrs.remove(r.id);
    }
    try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);}catch(Throwable ignored){}
    Method ids=type.getMethod("getInputDeviceIds");long prerollEnd=System.nanoTime()+300000000L;int calls=0;while(System.nanoTime()<prerollEnd){if((calls&63)==0)ids.invoke(manager);calls++;}
    long startMono=System.nanoTime()+100000000L,startUptimeNs=SystemClock.uptimeNanos()+100000000L,downTime=0;
    System.out.println("START,"+System.currentTimeMillis()+","+SystemClock.elapsedRealtimeNanos());
    StringBuilder log=new StringBuilder();
    for(Object[] ev:events){long ms=(Long)ev[0];int action=(Integer)ev[1];MotionEvent.PointerProperties[] props=(MotionEvent.PointerProperties[])ev[2];MotionEvent.PointerCoords[] coords=(MotionEvent.PointerCoords[])ev[3];
      long target=startMono+ms*1000000L;while(System.nanoTime()<target){}
      long eventTime=(startUptimeNs+ms*1000000L)/1000000L;if(action==MotionEvent.ACTION_DOWN)downTime=eventTime;
      MotionEvent event=MotionEvent.obtain(downTime,eventTime,action,props.length,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
      long before=System.nanoTime();boolean accepted=(Boolean)inject.invoke(manager,event,0);long after=System.nanoTime();event.recycle();
      log.append("EVENT,").append(ms).append(",").append(MotionEvent.actionToString(action)).append(",n=").append(props.length).append(",late_us=").append((before-target)/1000).append(",inject_us=").append((after-before)/1000).append(",").append(accepted).append("\n");
      if(!accepted)throw new IllegalStateException("Input injection rejected at "+ms);
    }
    System.out.print(log);System.out.println("END,"+SystemClock.elapsedRealtimeNanos());
  }
  static int indexOf(LinkedHashMap<Integer,int[]> ptrs,int id){int i=0;for(Integer k:ptrs.keySet()){if(k==id)return i;i++;}throw new IllegalArgumentException("unknown pointer "+id);}
}
