"""Run the patched Java card selector against asynchronous media refresh cases."""
from pathlib import Path
import re, subprocess, sys, tempfile
source=(Path(sys.argv[1])/'fermata/src/main/java/me/aap/fermata/ui/fragment/DashboardFragment.java').read_text()
method=re.search(r'private int findSmartTopCardPosition\(\) \{.*?\n\t\t\}',source,re.S).group()
program='''import java.util.*;
public class DashboardIdentityTest {
 static class Card {
  final boolean fixed,wide,recentShelf;
  Card(boolean f,boolean w,boolean r){fixed=f;wide=w;recentShelf=r;}
 }
 final List<Card> cards=new ArrayList<>();
 METHOD
 public static void main(String[] args) {
  DashboardIdentityTest t=new DashboardIdentityTest();
  Card journey=new Card(true,false,false);
  Card favorite=new Card(true,true,true);
  Card media=new Card(true,true,false);
  t.cards.add(journey);t.cards.add(favorite);
  if(t.findSmartTopCardPosition()!=-1)throw new AssertionError("Journey mistaken for media");
  t.cards.add(media);
  for(int i=0;i<30;i++) {
   int position=t.findSmartTopCardPosition();
   if(position!=2)throw new AssertionError("Wrong media refresh target");
   t.cards.set(position,new Card(true,true,false));
   if(t.cards.get(0)!=journey)throw new AssertionError("Journey overwritten");
  }
  Collections.swap(t.cards,0,2);
  if(t.findSmartTopCardPosition()!=0)throw new AssertionError("Reordered media not found");
  System.out.println("PASS: journey survives asynchronous media refresh and reordering");
 }
}'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as directory:
 p=Path(directory)/'DashboardIdentityTest.java';p.write_text(program)
 subprocess.run(['javac',str(p)],check=True)
 subprocess.run(['java','-cp',directory,'DashboardIdentityTest'],check=True)
