package ai.dubstudio.khpro;
import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  private static final int PICK=10, SAVE_ORIGINAL=11, SAVE_KHMER=12;
  final ExecutorService executor=Executors.newSingleThreadExecutor();
  final Handler handler=new Handler(Looper.getMainLooper());
  Uri selectedVideo;
  String fileName="video.mp4", server="", token="", jobId="";
  boolean busy=false, originalReady=false, khmerReady=false;
  EditText serverInput,tokenInput;
  TextView status,filename,results;
  Button process,choose,saveOriginal,saveKhmer;
  ProgressBar bar;
  CheckBox scan;
  VideoView video;
  int bg=Color.rgb(21,20,39), panel=Color.rgb(35,31,62), text=Color.rgb(238,234,255), mute=Color.rgb(183,173,216), purple=Color.rgb(138,119,255);
  LinearLayout main;

  GradientDrawable shape(int color,int radius) {
    GradientDrawable g=new GradientDrawable();
    g.setColor(color);g.setCornerRadius(radius);return g;
  }
  TextView title(String s,int sz,boolean bold) {
    TextView t=new TextView(this);t.setText(s);t.setTextSize(sz);t.setTextColor(text);
    if(bold)t.setTypeface(null,Typeface.BOLD);t.setPadding(0,6,0,10);return t;
  }
  LinearLayout card(){
    LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(18,16,18,18);
    l.setBackground(shape(panel,22));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=16;
    main.addView(l,p);return l;
  }
  Button button(String caption,LinearLayout parent,boolean accent){
    Button b=new Button(this);b.setText(caption);b.setAllCaps(false);b.setTextColor(text);
    b.setBackground(shape(accent?purple:Color.rgb(60,50,95),16));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=10;p.bottomMargin=4;
    parent.addView(b,p);return b;
  }
  EditText field(String hint,LinearLayout parent,boolean password){
    EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(text);e.setHintTextColor(mute);
    e.setTextSize(14);e.setHint(hint);e.setPadding(16,10,16,10);
    e.setInputType(password?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
    e.setBackground(shape(Color.rgb(45,41,74),12));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=8;
    parent.addView(e,p);return e;
  }
  void notifyUser(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  void refresh(){
    process.setEnabled(!busy&&selectedVideo!=null);choose.setEnabled(!busy);
    saveOriginal.setEnabled(!busy&&originalReady);saveKhmer.setEnabled(!busy&&khmerReady);
  }
  @Override public void onCreate(Bundle b){
    super.onCreate(b);getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
    ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(bg);
    main=new LinearLayout(this);main.setOrientation(1);main.setPadding(18,20,18,24);scroll.addView(main);
    setContentView(scroll);
    main.addView(title("✦ DubStudio KH Pro",25,true));
    TextView subtitle=title("MOBILE · VIDEO → ORIGINAL SUB → KHMER SRT",12,false);subtitle.setTextColor(mute);main.addView(subtitle);
    LinearLayout upload=card();upload.addView(title("01 · Upload Video",18,true));
    video=new VideoView(this);video.setBackgroundColor(Color.rgb(17,16,31));
    upload.addView(video,new LinearLayout.LayoutParams(-1,210));
    video.setMediaController(new MediaController(this));
    filename=title("No video selected",13,false);upload.addView(filename);
    choose=button("＋ Choose Video",upload,false);
    choose.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("video/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);});
    LinearLayout ai=card();ai.addView(title("02 · AI Subtitles",18,true));
    scan=new CheckBox(this);scan.setText("Scan Characters / Speakers (optional)");scan.setTextColor(text);scan.setChecked(true);ai.addView(scan);
    TextView note=title("Speaker detection requires a configured AI server. It identifies voices, not actual character names.",12,false);note.setTextColor(mute);ai.addView(note);
    serverInput=field("https://your-ai-backend.example.com",ai,false);
    tokenInput=field("Private API token",ai,true);
    process=button("✦ Generate Khmer Subtitles",ai,true);
    process.setOnClickListener(v->start());
    bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);ai.addView(bar,new LinearLayout.LayoutParams(-1,16));
    status=title("0% · Ready",14,true);ai.addView(status);
    LinearLayout output=card();output.addView(title("03 · Original & Khmer Subtitles",18,true));
    results=title("Subtitles will appear here after processing.",14,false);
    results.setTextIsSelectable(true);
    results.setBackground(shape(Color.rgb(29,26,51),12));results.setPadding(14,16,14,16);
    output.addView(results,new LinearLayout.LayoutParams(-1,-2));
    saveOriginal=button("↓ Save Original SRT",output,false);saveKhmer=button("↓ Save Khmer SRT",output,true);
    saveOriginal.setOnClickListener(v->save(false));saveKhmer.setOnClickListener(v->save(true));
    refresh();
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri u=data.getData();
    if(req==PICK){selectedVideo=u;filename.setText(u.getLastPathSegment());
      try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
      video.setVideoURI(u);video.seekTo(100);
      originalReady=false;khmerReady=false;results.setText("Video loaded. Configure your AI backend and generate subtitles.");refresh();
    } else if(req==SAVE_ORIGINAL||req==SAVE_KHMER)writeSrt(u,req==SAVE_KHMER);
  }
  String errorBody(HttpURLConnection c) throws IOException {
    InputStream in=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream();
    if(in==null)return "";
    try(ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream source=in){
      byte[] bytes=new byte[8192];int n;while((n=source.read(bytes))!=-1)out.write(bytes,0,n);
      String s=out.toString("UTF-8");
      if(c.getResponseCode()>=400)throw new IOException("HTTP "+c.getResponseCode()+": "+s);
      return s;
    }
  }
  HttpURLConnection open(String path,String method)throws IOException{
    HttpURLConnection c=(HttpURLConnection)new URL(server+path).openConnection();
    c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(300000);
    c.setRequestProperty("Authorization","Bearer "+token);return c;
  }
  void start(){
    if(selectedVideo==null){notifyUser("Choose a video first");return;}
    server=serverInput.getText().toString().trim().replaceAll("/+$","");
    token=tokenInput.getText().toString().trim();
    if(!server.startsWith("https://")||token.isEmpty()){notifyUser("HTTPS AI Backend and API Token are required");return;}
    busy=true;originalReady=false;khmerReady=false;bar.setProgress(1);status.setText("1% · Uploading video");refresh();
    executor.execute(()->{
      try {
        String boundary="DubStudio"+UUID.randomUUID().toString().replace("-","");
        HttpURLConnection c=open("/api/jobs","POST");
        c.setDoOutput(true);c.setChunkedStreamingMode(65536);
        c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        try(OutputStream out=c.getOutputStream()){
          send(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"scan_speakers\"\r\n\r\n"+(scan.isChecked()?"true":"false")+"\r\n");
          send(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"source_language\"\r\n\r\nauto\r\n");
          send(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"video\"; filename=\"upload.mp4\"\r\nContent-Type: video/mp4\r\n\r\n");
          try(InputStream source=getContentResolver().openInputStream(selectedVideo)){
            if(source==null)throw new IOException("Video cannot be opened");byte[] buf=new byte[65536];int n;
            while((n=source.read(buf))!=-1)out.write(buf,0,n);
          }
          send(out,"\r\n--"+boundary+"--\r\n");
        }
        JSONObject j=new JSONObject(errorBody(c));c.disconnect();jobId=j.getString("job_id");
        uiStatus(5,"Video uploaded");poll();
      }catch(Exception e){fail(e);}
    });
  }
  void send(OutputStream out,String s)throws IOException{out.write(s.getBytes(StandardCharsets.UTF_8));}
  void uiStatus(int n,String msg){runOnUiThread(()->{bar.setProgress(n);status.setText(n+"% · "+msg);});}
  String get(String path)throws Exception{HttpURLConnection c=open(path,"GET");try{return errorBody(c);}finally{c.disconnect();}}
  void poll(){
    try{
      JSONObject j=new JSONObject(get("/api/jobs/"+jobId));
      String stage=j.optString("stage","queued");int pct=j.optInt("percent",5);
      uiStatus(pct,j.optString("message",stage));
      if("done".equals(stage)||"failed".equals(stage)){
        originalReady=j.optBoolean("original_ready","done".equals(stage));
        khmerReady=j.optBoolean("khmer_ready","done".equals(stage));
        busy=false;runOnUiThread(()->refresh());
        try{
          JSONObject r=new JSONObject(get("/api/jobs/"+jobId+"/result"));
          JSONArray subs=r.optJSONArray("subtitles");
          StringBuilder s=new StringBuilder();
          if(subs!=null)for(int i=0;i<Math.min(80,subs.length());i++){
            JSONObject line=subs.getJSONObject(i);
            s.append(i+1).append(". [").append(line.optString("speaker","SPEAKER_01")).append("] ");
            s.append(line.optString("original")).append("\n");
            s.append("ខ្មែរ: ").append(line.optString("khmer","—")).append("\n\n");
          }
          runOnUiThread(()->results.setText(s.toString()));
        }catch(Exception ignored){}
        if("failed".equals(stage))uiStatus(pct,"Translation failed; original SRT may still be available");
      } else {handler.postDelayed(()->executor.execute(this::poll),1800);}
    }catch(Exception e){fail(e);}
  }
  void fail(Exception e){busy=false;runOnUiThread(()->{refresh();status.setText("Error: "+e.getMessage());notifyUser(e.getMessage());});}
  void save(boolean kh){
    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.setType("text/plain");i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_TITLE,kh?"DubStudio_Khmer.srt":"DubStudio_Original.srt");
    startActivityForResult(i,kh?SAVE_KHMER:SAVE_ORIGINAL);
  }
  void writeSrt(Uri destination,boolean kh){
    executor.execute(()->{
      try{
        HttpURLConnection c=open("/api/jobs/"+jobId+"/srt?kind="+(kh?"km":"original"),"GET");
        byte[] bytes=errorBody(c).getBytes(StandardCharsets.UTF_8);c.disconnect();
        try(OutputStream out=getContentResolver().openOutputStream(destination)){out.write(bytes);}
        runOnUiThread(()->notifyUser("Saved SRT ✓"));
      }catch(Exception e){fail(e);}
    });
  }
  @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}