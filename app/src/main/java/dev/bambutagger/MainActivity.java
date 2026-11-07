package dev.bambutagger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Touch-first native Android UI. All NFC I/O and GitHub network requests run off the UI thread.
 * This app uses Android's standard MifareClassic API; it cannot unlock Gen 1 magic tags.
 */
public final class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private static final int BACKGROUND = 0xFF0D141B;
    private static final int PANEL = 0xFF1B2832;
    private static final int PANEL2 = 0xFF23333F;
    private static final int TEXT = 0xFFF3F7F9;
    private static final int MUTED = 0xFFA9BBC5;
    private static final int ACCENT = 0xFF68E5B8;
    private static final int CAUTION = 0xFFFFD18A;
    private static final int ERROR = 0xFFFF9797;
    private static final String PREFS = "bambu_tagger";

    private enum TapAction { NONE, INSPECT, PREVIEW_WRITE, WRITE, VERIFY }
    private volatile TapAction action = TapAction.NONE;
    private String previewUid = "";
    private String previewTargetUid = "";
    // The experimental mode is deliberately opt-in per launch.
    private boolean reusableMode = false;
    private TagCloner.WriteMode armedMode = TagCloner.WriteMode.AUTHENTIC_CLONE;
    private TagCloner.WriteMode verifyAfterWriteMode = null;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Starter> starters = new ArrayList<>();
    private final List<GitHubCatalog.Entry> browseItems = new ArrayList<>();
    private String browsePath = "";
    private String status = "Select a filament to begin, or tap Read to inspect a tag.";
    private boolean browsing = false;
    private boolean loading = false;
    private NfcAdapter adapter;
    private GitHubCatalog catalog;
    private TagCloner cloner;
    private BambuDump selected;
    private String selectedLabel = "";
    private String selectedNote = "";
    private LinearLayout base;
    private LinearLayout body;
    private LinearLayout savedList;
    private LinearLayout browseList;
    private TextView statusView;
    private TextView readButton;
    private TextView writeButton;
    private TextView verifyButton;

    private static final class Starter {
        final String filename, alias, bambu, color, uid, note;
        Starter(JSONObject j) {
            filename=j.optString("filename");
            alias=j.optString("alias");
            bambu=j.optString("bambu");
            color=j.optString("color", "#888888");
            uid=j.optString("uid");
            note=j.optString("note", "");
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        adapter=NfcAdapter.getDefaultAdapter(this);
        cloner=new TagCloner(this);
        String repo=getPreferences(0).getString("repository",GitHubCatalog.DEFAULT_REPO);
        try { catalog=new GitHubCatalog(this,repo); }
        catch(Exception e) { catalog=new GitHubCatalog(this,GitHubCatalog.DEFAULT_REPO); }
        loadStarters();
        if (adapter==null) status="No NFC adapter found on this device. Library browsing remains available.";
        else if (!adapter.isEnabled()) status="Enable NFC in Android settings before scanning or writing.";
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if(adapter!=null && adapter.isEnabled()) {
            Bundle options=new Bundle();
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,400);
            adapter.enableReaderMode(this,this,NfcAdapter.FLAG_READER_NFC_A |
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,options);
        }
    }
    @Override protected void onPause() {
        if(adapter!=null) try {adapter.disableReaderMode(this);} catch(Exception ignored) {}
        action=TapAction.NONE;
        super.onPause();
    }
    @Override protected void onDestroy() {worker.shutdownNow();super.onDestroy();}

    private void loadStarters() {
        try(InputStream input=getAssets().open("starter/index.json")) {
            JSONArray a=new JSONArray(new String(readAll(input),StandardCharsets.UTF_8));
            for(int i=0;i<a.length();i++) starters.add(new Starter(a.getJSONObject(i)));
        } catch(Exception e) { status="Offline library couldn't load: "+e.getMessage(); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;
        while((n=in.read(bytes))>=0) out.write(bytes,0,n);
        return out.toByteArray();
    }

    private int dp(float value) {return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    private GradientDrawable shape(int fill, float radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));return d;
    }
    private GradientDrawable outline(int fill,float radius,int stroke) {
        GradientDrawable d=shape(fill,radius);d.setStroke(dp(1),stroke);return d;
    }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l; }
    private LinearLayout row() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setIncludeFontPadding(true);t.setLineSpacing(dp(2),1f);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private LinearLayout.LayoutParams lp(int w,int h) {return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void space(LinearLayout l,int height) {View v=new View(this);l.addView(v,lp(1,height));}
    private void padding(View v,int horizontal,int vertical) {v.setPadding(dp(horizontal),dp(vertical),dp(horizontal),dp(vertical));}
    private int parse(String hex) {try{return Color.parseColor(hex);}catch(Exception e){return 0xFF8D929A;}}
    private TextView chip(String text,int color, int bg) {
        TextView view=text(text,12,color,true);padding(view,11,6);view.setBackground(shape(bg,30));view.setGravity(Gravity.CENTER);return view;
    }
    private TextView press(String label,int color,int textColor) {
        TextView button=text(label,15,textColor,true);button.setGravity(Gravity.CENTER);
        button.setBackground(shape(color,14));button.setMinHeight(dp(54));
        button.setClickable(true);button.setFocusable(true);
        padding(button,12,12);return button;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card=column();card.setBackground(outline(PANEL,18,0xFF344651));
        card.setPadding(dp(16),dp(14),dp(16),dp(14));
        LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(10);
        parent.addView(card,p);return card;
    }

    private void render() {
        base=column();base.setBackgroundColor(BACKGROUND);
        setContentView(base);
        LinearLayout header=column();padding(header,20,16);base.addView(header,lp(-1,-2));
        LinearLayout heading=row();header.addView(heading);
        LinearLayout headline=column();heading.addView(headline,new LinearLayout.LayoutParams(0,-2,1));
        headline.addView(text("BAMBU TAGGER",23,TEXT,true));
        headline.addView(text("Community RFID  ·  Android NFC",12,MUTED,false));
        TextView sync=chip("v0.2 dev",ACCENT,PANEL2);heading.addView(sync);
        space(header,16);
        LinearLayout tabs=row();header.addView(tabs,lp(-1,-2));
        TextView home=press("My filaments",browsing?PANEL2:ACCENT,browsing?TEXT:BACKGROUND);
        TextView online=press("Browse GitHub",browsing?ACCENT:PANEL2,browsing?BACKGROUND:TEXT);
        LinearLayout.LayoutParams h=new LinearLayout.LayoutParams(0,dp(50),1);h.rightMargin=dp(5);tabs.addView(home,h);
        LinearLayout.LayoutParams n=new LinearLayout.LayoutParams(0,dp(50),1);n.leftMargin=dp(5);tabs.addView(online,n);
        home.setOnClickListener(v->{browsing=false;render();});
        online.setOnClickListener(v->{browsing=true;render();if(browseItems.isEmpty())navigate(browsePath);});

        ScrollView scroller=new ScrollView(this);
        scroller.setFillViewport(false);
        base.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        body=column();body.setPadding(dp(18),dp(2),dp(18),dp(10));scroller.addView(body);
        if(browsing)renderBrowse();else renderHome();
        renderFooter();
    }

    private void renderHome() {
        if(selected!=null) {
            LinearLayout panel=card(body);
            panel.addView(text("SELECTED FILAMENT",12,ACCENT,true));space(panel,8);
            LinearLayout r=row();panel.addView(r,lp(-1,-2));
            View swatch=new View(this);swatch.setBackground(shape(parse(selected.colorRgb()),12));r.addView(swatch,lp(52,52));
            LinearLayout labels=column();labels.setPadding(dp(12),0,0,0);r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            labels.addView(text(selectedLabel,17,TEXT,true));labels.addView(text(selected.filament+"  ·  "+selected.family,13,MUTED,false));
            space(panel,8);panel.addView(text("UID  "+selected.uidHex,13,ACCENT,true));
            if(!selectedNote.isEmpty()){space(panel,8);panel.addView(text(selectedNote,13,CAUTION,false));}
            space(panel,4);
            panel.addView(text("Use a compatible 13.56 MHz Gen 2/CUID fob. Never use a genuine Bambu tag.",12,MUTED,false));
            space(panel,12);
            TextView modeSwitch=press(reusableMode
                    ? "EXPERIMENTAL Reusable Mode: ON"
                    : "Reusable Mode: OFF  ·  Exact clone",
                    reusableMode?CAUTION:PANEL2,reusableMode?BACKGROUND:TEXT);
            modeSwitch.setContentDescription("Toggle experimental reusable tag writing. Currently "+(reusableMode?"on":"off"));
            panel.addView(modeSwitch,lp(-1,56));
            modeSwitch.setOnClickListener(v->changeWriteMode());
            space(panel,7);
            panel.addView(text(reusableMode
                    ? "Writes writable FF078069 access permissions. Android may verify it but AMS acceptance is UNKNOWN."
                    : "Default: exact Bambu sector trailer permissions (87878769). Clones are normally one-time use.",
                    12,reusableMode?CAUTION:MUTED,false));
            space(body,8);
        }
        LinearLayout r=row();body.addView(r,lp(-1,-2));
        TextView title=text("Saved spool matches",19,TEXT,true);r.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        r.addView(chip(starters.size()+" offline",ACCENT,PANEL2));space(body,8);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);search.setTextSize(15);search.setHint("Search brand, color, or material");
        search.setBackground(outline(PANEL,14,0xFF40525D));
        search.setPadding(dp(16),dp(13),dp(12),dp(13));
        search.setMinimumHeight(dp(52));body.addView(search,lp(-1,-2));space(body,12);
        savedList=column();body.addView(savedList,lp(-1,-2));fillStarters("");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){fillStarters(s.toString());}
            public void afterTextChanged(Editable e){}
        });
        LinearLayout live=card(body);
        live.addView(text("FIRST LIVE-LIBRARY TEST",12,ACCENT,true));space(live,6);
        live.addView(text("Sunlu Yellow  →  Bambu PLA Basic Yellow",15,TEXT,true));
        space(live,6);live.addView(text("Open the Yellow folder online, choose a genuine signed UID, and load it without importing files.",13,MUTED,false));
        space(live,12);
        TextView browse=press("Find Yellow in GitHub",PANEL2,TEXT);
        live.addView(browse,lp(-1,52));
        browse.setOnClickListener(v->{browsing=true;browsePath="PLA/PLA Basic/Yellow";browseItems.clear();render();navigate(browsePath);});
    }

    private void fillStarters(String query) {
        if(savedList==null)return;
        savedList.removeAllViews();String q=query.toLowerCase(Locale.US).trim();int matches=0;
        for (Starter item:starters) {
            if(!q.isEmpty() && !(item.alias+" "+item.bambu+" "+item.uid).toLowerCase(Locale.US).contains(q))continue;
            matches++;
            LinearLayout c=card(savedList);c.setClickable(true);c.setFocusable(true);
            LinearLayout line=row();c.addView(line,lp(-1,-2));
            View swatch=new View(this);swatch.setBackground(shape(parse(item.color),10));line.addView(swatch,lp(48,48));
            LinearLayout words=column();words.setPadding(dp(14),0,0,0);
            line.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            words.addView(text(item.alias,16,TEXT,true));words.addView(text(item.bambu,13,MUTED,false));
            space(c,6);
            LinearLayout foot=row();c.addView(foot,lp(-1,-2));
            foot.addView(text("UID "+item.uid,12,ACCENT,true),new LinearLayout.LayoutParams(0,-2,1));
            TextView select=text("Select  ›",14,ACCENT,true);foot.addView(select);
            if(!item.note.isEmpty()){space(c,7);c.addView(text(item.note,12,CAUTION,false));}
            c.setContentDescription(item.alias+", "+item.bambu+". Select filament");
            c.setOnClickListener(v->selectStarter(item));
        }
        if(matches==0)savedList.addView(text("No saved matches. Browse GitHub for a new color.",15,MUTED,false));
    }

    private void selectStarter(Starter item) {
        try(InputStream in=getAssets().open("starter/"+item.filename)) {
            BambuDump dump=BambuDump.fromBinary(readAll(in));
            if(!dump.uidHex.equalsIgnoreCase(item.uid))throw new IllegalArgumentException("Packaged UID mismatch");
            selected=dump;selectedLabel=item.alias;selectedNote=item.note;verifyAfterWriteMode=null;
            status="Selected "+item.bambu+". Tap Write fob when ready.";
            action=TapAction.NONE;browsing=false;render();
        }catch(Exception e){showError("Cannot load bundled RFID data",e);}
    }

    private void renderBrowse() {
        body.addView(text("Community RFID library",19,TEXT,true));space(body,5);
        body.addView(text("Browse material → type → color → UID. The latest signed dump downloads from GitHub and is cached for offline use.",13,MUTED,false));
        space(body,10);
        LinearLayout buttons=row();body.addView(buttons,lp(-1,-2));
        TextView back=press("‹ Back",PANEL2,TEXT);
        TextView change=press("Repository",PANEL2,TEXT);
        LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,dp(50),1);b.rightMargin=dp(5);buttons.addView(back,b);
        LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(0,dp(50),1);c.leftMargin=dp(5);buttons.addView(change,c);
        back.setOnClickListener(v->{if(browsePath.isEmpty()){browsing=false;render();return;}int idx=browsePath.lastIndexOf('/');navigate(idx<0?"":browsePath.substring(0,idx));});
        change.setOnClickListener(v->repositoryDialog());
        space(body,10);
        LinearLayout bread=card(body);
        bread.addView(text("REPOSITORY",11,ACCENT,true));space(bread,4);
        bread.addView(text(catalog.getRepository(),14,TEXT,true));
        space(bread,4);bread.addView(text("/"+(browsePath.isEmpty()?"":browsePath),13,MUTED,false));
        if(loading){space(body,8);body.addView(text("Loading GitHub directory…",15,CAUTION,false));}
        EditText filter=new EditText(this);filter.setSingleLine(true);filter.setHint("Filter this folder");
        filter.setTextColor(TEXT);filter.setHintTextColor(MUTED);filter.setTextSize(15);
        filter.setPadding(dp(16),dp(11),dp(12),dp(11));filter.setBackground(outline(PANEL,12,0xFF40525D));
        filter.setMinimumHeight(dp(51));body.addView(filter,lp(-1,-2));space(body,11);
        browseList=column();body.addView(browseList,lp(-1,-2));fillBrowse("");
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){fillBrowse(s.toString());}
            public void afterTextChanged(Editable e){}
        });
    }

    private void fillBrowse(String query) {
        if(browseList==null)return;browseList.removeAllViews();String q=query.toLowerCase(Locale.US).trim();int count=0;
        for(GitHubCatalog.Entry e:browseItems) {
            if(!q.isEmpty()&&!e.name.toLowerCase(Locale.US).contains(q))continue;
            count++;
            LinearLayout row=card(browseList);row.setClickable(true);row.setFocusable(true);
            String name=e.name.endsWith("-dump.json")?"Load RFID scan":""+e.name;
            String icon=e.directory ? "▣" : "↓";
            row.addView(text(icon+"   "+name,16,TEXT,true));
            if(e.directory && e.path.split("/").length>=4)
                row.addView(text("Signed RFID scan · tap to load",12,ACCENT,false));
            row.setOnClickListener(v->{if(e.directory){if(e.path.split("/").length>=4)loadRemoteDump(e.path);else navigate(e.path);}else loadRemoteDump(browsePath);});
        }
        if(count==0 && !loading)browseList.addView(text("No items shown. Try a different filter or folder.",14,MUTED,false));
    }

    private void navigate(String path) {
        browsePath=path;loading=true;browseItems.clear();render();
        worker.execute(()->{
            try {
                List<GitHubCatalog.Entry> items=catalog.list(path);
                main.post(()->{if(!browsePath.equals(path))return;loading=false;browseItems.clear();browseItems.addAll(items);render();status="Loaded "+items.size()+" items from "+catalog.getRepository();refreshStatus();});
            } catch(Exception e){main.post(()->{loading=false;render();showError("GitHub directory unavailable",e);});}
        });
    }

    private void loadRemoteDump(String path) {
        status="Downloading signed RFID identity from GitHub…";refreshStatus();
        worker.execute(()->{
            try {
                BambuDump dump=catalog.loadDump(path);
                main.post(()->{
                    selected=dump;verifyAfterWriteMode=null;selectedLabel="Bambu "+dump.filament+"  ·  "+lastColor(path);
                    selectedNote="Downloaded community dump; Bambu brand identity. Keep its signed data unchanged.";
                    browsing=false;action=TapAction.NONE;
                    status="Downloaded and checked UID "+dump.uidHex+". Ready for writing.";render();
                });
            } catch(Exception e){main.post(()->showError("Cannot load this RFID scan",e));}
        });
    }
    private String lastColor(String path) {
        String[] names=path.split("/");return names.length>=3?names[names.length-2]:path;
    }

    private void repositoryDialog() {
        EditText edit=new EditText(this);edit.setText(catalog.getRepository());
        edit.setSingleLine(true);edit.setSelectAllOnFocus(true);edit.setTextColor(TEXT);
        FrameLayout wrap=new FrameLayout(this);wrap.setPadding(dp(22),0,dp(22),0);wrap.addView(edit);
        new AlertDialog.Builder(this).setTitle("Community repository")
            .setMessage("Enter GitHub owner/repository. The app supports the Bambu RFID Library folder structure and -dump.json scans.")
            .setView(wrap).setNegativeButton("Cancel",null)
            .setPositiveButton("Use repository",(dialog,which)->{
                try {
                    String repo=edit.getText().toString().trim();catalog.setRepository(repo);
                    getPreferences(0).edit().putString("repository",repo).apply();navigate("");
                }catch(Exception e){showError("Invalid repository",e);}
            }).show();
    }

    private void renderFooter() {
        LinearLayout footer=column();footer.setPadding(dp(18),dp(12),dp(18),dp(19));
        footer.setBackground(outline(0xFF14202A,16,0xFF354652));base.addView(footer,lp(-1,-2));
        statusView=text(status,13,status.toLowerCase(Locale.US).contains("failed")?ERROR:MUTED,false);
        statusView.setMinHeight(dp(32));footer.addView(statusView,lp(-1,-2));space(footer,8);
        LinearLayout actions=row();footer.addView(actions,lp(-1,-2));
        readButton=press("Read",PANEL2,TEXT);
        writeButton=press("Write fob",ACCENT,BACKGROUND);
        verifyButton=press("Verify",PANEL2,TEXT);
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,dp(56),1);a.rightMargin=dp(5);actions.addView(readButton,a);
        LinearLayout.LayoutParams w=new LinearLayout.LayoutParams(0,dp(56),1.4f);w.rightMargin=dp(5);actions.addView(writeButton,w);
        LinearLayout.LayoutParams v=new LinearLayout.LayoutParams(0,dp(56),1);actions.addView(verifyButton,v);
        readButton.setOnClickListener(t->arm(TapAction.INSPECT,"Tap a MIFARE Classic tag to read its UID and filament data."));
        writeButton.setEnabled(selected!=null);writeButton.setAlpha(selected!=null?1f:.4f);
        writeButton.setOnClickListener(t->beginWrite());
        verifyButton.setEnabled(selected!=null);verifyButton.setAlpha(selected!=null?1f:.4f);
        verifyButton.setOnClickListener(t->{verifyAfterWriteMode=null;arm(TapAction.VERIFY,"Tap the fob to verify UID, memory, keys and "+(reusableMode?"reusable":"Bambu")+" permissions.");});
    }

    private TagCloner.WriteMode currentWriteMode() {
        return reusableMode ? TagCloner.WriteMode.REUSABLE_EXPERIMENTAL
                            : TagCloner.WriteMode.AUTHENTIC_CLONE;
    }

    private void changeWriteMode() {
        if(reusableMode) {
            reusableMode=false;
            action=TapAction.NONE;
            verifyAfterWriteMode=null;
            status="Exact Bambu clone mode selected. Completed clones are normally read-only.";
            render();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Experimental Reusable Tag Mode")
            .setMessage("WARNING: This is an untested alteration of the Bambu dump. The app will retain factory-writable sector access bytes (FF078069), rather than Bambu's locked access bytes (87878769).\n\nThe AMS 2 Pro may reject the tag, modify it, or leave the tag unusable.\n\nTest on an expendable blue CUID fob only. Verify on Android AND test AMS recognition before attempting any rewrite. Never use a genuine Bambu spool.")
            .setNegativeButton("Keep exact clone",null)
            .setPositiveButton("Enable experiment",(dialog,which)->{
                reusableMode=true;
                action=TapAction.NONE;
                verifyAfterWriteMode=null;
                status="Experimental reusable mode ON. AMS compatibility unknown.";
                render();
            }).show();
    }

    private void beginWrite() {
        if(selected==null){status="Select a filament before writing.";refreshStatus();return;}
        if(adapter==null||!adapter.isEnabled()){status="Please enable NFC before writing.";refreshStatus();return;}
        String message="Selected: "+selectedLabel+"\n\nExpected UID: "+selected.uidHex+
            "\n\nWRITE MODE: "+(reusableMode?"EXPERIMENTAL reusable (AMS acceptance UNKNOWN)":"Exact clone (normally one-time use)")+
            "\n\nA compatible UID-changeable MIFARE Classic 1K fob is required. " +
            "Writing can permanently damage an incompatible tag. Do NOT scan a genuine Bambu spool. " +
            "You will first scan the destination fob and then confirm its exact UID before the app writes anything.";
        new AlertDialog.Builder(this).setTitle("Prepare to clone RFID tag")
            .setMessage(message).setNegativeButton("Cancel",null)
            .setPositiveButton("Scan destination",(d,w)->arm(TapAction.PREVIEW_WRITE,"Tap the FRESH, factory-formatted fob once for a read-only check."))
            .show();
    }

    private void arm(TapAction next,String message) {
        if(adapter==null){status="This device has no NFC adapter.";refreshStatus();return;}
        if(!adapter.isEnabled()){status="NFC is disabled. Turn it on in Android settings.";refreshStatus();return;}
        action=next;status=message;refreshStatus();
    }
    private void refreshStatus() {if(statusView!=null)statusView.setText(status);}
    private void setStatus(String s) {main.post(()->{status=s;refreshStatus();});}
    private void showError(String title,Exception error) {
        status=title+": "+error.getMessage();refreshStatus();
        new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage()).setPositiveButton("OK",null).show();
    }

    @Override public void onTagDiscovered(Tag tag) {
        final TapAction todo=action;
        if(todo==TapAction.NONE || !busy.compareAndSet(false,true))return;
        action=TapAction.NONE; // Consume each NFC discovery at most once.
        // NFC activity is serialized; no UI/blocking work is executed in the reader callback.
        worker.execute(()->{
            try {
                if(todo==TapAction.INSPECT) {
                    TagCloner.CardInfo info=cloner.inspect(tag);
                    main.post(()->{
                        action=TapAction.NONE;
                        status="Read UID "+info.uid+"; "+info.readableSectors+"/"+info.sectors+" sectors available.";refreshStatus();
                        new AlertDialog.Builder(this).setTitle("RFID tag scanned")
                            .setMessage("UID: "+info.uid+"\nSectors readable: "+info.readableSectors+"/16\nFilament family: "+info.material+
                                "\nBambu-locked sectors: "+info.lockedBambuSectors+
                                "\nFactory-writable access: "+(info.factoryWritable?"Yes":"No / unverified"))
                            .setPositiveButton("OK",null).show();
                    });
                }else if(todo==TapAction.PREVIEW_WRITE){
                    TagCloner.CardInfo info=cloner.inspect(tag);
                    main.post(()->{
                        action=TapAction.NONE;
                        if(info.readableSectors!=16 || !info.factoryWritable) {
                            status="Destination is not a verified factory-writable tag. No write armed.";refreshStatus();
                            String detail=info.readableSectors!=16
                                ? "Only "+info.readableSectors+"/16 sectors could be read."
                                : info.lockedBambuSectors>0
                                ? info.lockedBambuSectors+" sectors have Bambu read-only permissions (87878769). A successfully cloned CUID fob is normally NOT reusable."
                                : "Some sectors do not have factory transport permissions (FF078069).";
                            new AlertDialog.Builder(this).setTitle("Destination not writable")
                                .setMessage(detail+"\n\nUse a fresh, known-working blue CUID fob. No blocks were modified.")
                                .setPositiveButton("OK",null).show();return;
                        }
                        String source=info.material==null||info.material.isEmpty()?"Unknown":info.material;
                        new AlertDialog.Builder(this).setTitle("Confirm destination tag")
                            .setMessage("Destination UID: "+info.uid+"\nCurrent data: "+source+
                                "\n\nProgram as: "+selectedLabel+"\nNew UID: "+selected.uidHex+
                                "\n\nMode: "+(reusableMode?"EXPERIMENTAL writable permissions":"Exact Bambu clone")+
                                "\n\nThis rewrites all 16 sectors and the UID. Some tags cannot be restored. " +
                                "Only continue if this is your expendable fresh, factory-formatted keyfob. REMOVE it now; you must tap it again to write.")
                            .setNegativeButton("Cancel",null)
                            .setPositiveButton("Arm write",(d,which)->{
                                previewUid=info.uid;
                                previewTargetUid=selected.uidHex;
                                armedMode=currentWriteMode();
                                arm(TapAction.WRITE,"Write armed for UID "+previewUid+" ("+(armedMode==TagCloner.WriteMode.REUSABLE_EXPERIMENTAL?"EXPERIMENTAL":"exact")+"). Lift and tap SAME fob again; keep it still.");
                            }).show();
                    });
                }else if(todo==TapAction.WRITE) {
                    String found=Hex.encode(tag.getId());
                    if(!found.equalsIgnoreCase(previewUid)) throw new IllegalStateException("Different tag detected ("+found+"). Expected "+previewUid+"; no changes made.");
                    BambuDump target=selected;
                    if(target==null)throw new IllegalStateException("No filament selected");
                    if(!target.uidHex.equalsIgnoreCase(previewTargetUid))
                        throw new IllegalStateException("Filament changed after confirmation; no blocks modified.");
                    final TagCloner.WriteMode mode=armedMode;
                    setStatus("Writing "+target.uidHex+" ("+(mode==TagCloner.WriteMode.REUSABLE_EXPERIMENTAL?"reusable EXPERIMENT":"exact clone")+") — keep fob steady.");
                    String saved=cloner.write(tag,target,mode,this::setStatus);
                    main.post(()->{
                        action=TapAction.VERIFY;
                        verifyAfterWriteMode=mode;
                        status="Write finished ("+(mode==TagCloner.WriteMode.REUSABLE_EXPERIMENTAL?"EXPERIMENTAL":"exact")+"). LIFT and TAP AGAIN to verify.";refreshStatus();
                        new AlertDialog.Builder(this).setTitle("Write finished — verify next")
                            .setMessage("A write completion is not proof the tag works. Remove the fob, then tap it again for "+(mode==TagCloner.WriteMode.REUSABLE_EXPERIMENTAL?"experimental permission-aware":"exact clone")+" verification.\n\nBackup: "+saved)
                            .setPositiveButton("Verify with next tap",null).show();
                    });
                }else if(todo==TapAction.VERIFY) {
                    BambuDump target=selected;
                    if(target==null)throw new IllegalStateException("Select a filament first");
                    final TagCloner.WriteMode mode=verifyAfterWriteMode!=null?verifyAfterWriteMode:currentWriteMode();
                    String result=cloner.verify(tag,target,mode,this::setStatus);
                    main.post(()->{
                        action=TapAction.NONE;
                        status=(mode==TagCloner.WriteMode.REUSABLE_EXPERIMENTAL
                            ? "Verified experimental data "+target.uidHex+". AMS compatibility UNKNOWN."
                            : "Verified exact clone "+target.uidHex+". Ready to test in the AMS.");refreshStatus();
                        new AlertDialog.Builder(this).setTitle("Tag verified")
                            .setMessage(result+"\n\nSelected filament: "+selectedLabel+
                                (mode==TagCloner.WriteMode.REUSABLE_EXPERIMENTAL
                                    ?"\n\nNEXT: Test in AMS 2 Pro, then inspect the tag's sector permissions again. Do not rewrite until both checks succeed."
                                    :""))
                            .setPositiveButton("OK",null).show();
                    });
                }
            }catch(Exception e){
                main.post(()->{
                    action=TapAction.NONE;
                    status="NFC operation failed: "+e.getMessage();refreshStatus();
                    new AlertDialog.Builder(this).setTitle("NFC operation failed")
                        .setMessage(e.getMessage()+"\n\nIf writing was interrupted, the tag may be partially programmed. Do not assume a backup can fully restore the tag.")
                        .setPositiveButton("OK",null).show();
                });
            }finally {busy.set(false);}
        });
    }
}
