#!/usr/bin/env python3
"""Execute the real overlay router against a delegate that records add-on inputs."""
import pathlib
import subprocess
import tempfile
import sys

base = pathlib.Path(__file__).resolve().parents[1]
source = base / 'overlay/fermata/src/main/java/me/aap/fermata/ev/EvCockpitMediaEngineDestination.java'
ids = ['dashboard_fragment','tv_fragment','youtube_fragment','radio_fragment',
       'podcast_fragment','folders_fragment','web_browser_fragment','settings_fragment']
with tempfile.TemporaryDirectory() as temp:
    root = pathlib.Path(temp)
    files = {
        'me/aap/fermata/R.java': 'package me.aap.fermata; public class R { public static class id {' +
            ''.join(f'public static final int {name}={i};' for i,name in enumerate(ids)) + '}}',
        'me/aap/fermata/ui/activity/MainActivityDelegate.java': '''package me.aap.fermata.ui.activity;
public class MainActivityDelegate {
 public int active=42, requested=-1; public Object input; public boolean accept=true, dashboard=false;
 public int getActiveNavItemId(){return active;}
 public void setActiveNavItemId(int value){active=value;}
 public boolean showFragmentWhenReady(int id,Object value){requested=id;input=value;return accept;}
 public void returnToDashboard(){dashboard=true;}
}''',
        'me/aap/fermata/ui/activity/NavigationCoordinator.java': '''package me.aap.fermata.ui.activity;
public class NavigationCoordinator {
 public static boolean select(MainActivityDelegate d,int id){return d.showFragmentWhenReady(id,null);}
}''',
        'me/aap/fermata/ev/EvCockpitMediaEngineDestination.java': source.read_text(),
        'RouterTest.java': '''import me.aap.fermata.ev.EvCockpitMediaEngineDestination;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.R;
public class RouterTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  String[] targets={"netflix","disney","prime"};
  String[] urls={"https://www.netflix.com/","https://www.disneyplus.com/","https://www.primevideo.com/"};
  for(int i=0;i<targets.length;i++){
   MainActivityDelegate d=new MainActivityDelegate();
   EvCockpitMediaEngineDestination.navigate(d,targets[i]);
   check(d.requested==R.id.web_browser_fragment);check(urls[i].equals(d.input));
   check(!d.dashboard);
  }
  MainActivityDelegate disabled=new MainActivityDelegate();disabled.accept=false;
  EvCockpitMediaEngineDestination.navigate(disabled,"netflix");check(disabled.dashboard && disabled.active==42);
  MainActivityDelegate radio=new MainActivityDelegate();EvCockpitMediaEngineDestination.navigate(radio,"radio");
  check(radio.requested==R.id.radio_fragment && radio.input==null);
  EvCockpitMediaEngineDestination.navigate(null,"netflix");
  check(EvCockpitMediaEngineDestination.siteFor("https://evil.test")==null);
  check(EvCockpitMediaEngineDestination.fragmentFor("unknown")==R.id.dashboard_fragment);
 }
}''',
    }
    for path, text in files.items():
        target=root/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
    subprocess.run(['javac','-d',str(root/'classes'),*[str(root/p) for p in files]],check=True)
    subprocess.run(['java','-cp',str(root/'classes'),'RouterTest'],check=True)
if len(sys.argv)>1:
    # Media implementation must remain byte-identical to the pinned GPL checkout.
    subprocess.run(['git','-C',sys.argv[1],'diff','--exit-code','--',
                    'modules/tv','modules/web','modules/vlc','modules/exoplayer'],check=True)
print('PASS: embedded subscription routing, disabled add-on fallback, existing radio path, unchanged media engines')
