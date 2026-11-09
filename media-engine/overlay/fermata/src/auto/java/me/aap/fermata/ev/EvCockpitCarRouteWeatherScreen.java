package me.aap.fermata.ev;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Read-only weather on the chosen phone route, using actual Open-Meteo
 * results cached by EvCockpitNavigatorActivity. Cannot claim live weather radar.
 * No network calls while operating the Android Auto template host.
 */
final class EvCockpitCarRouteWeatherScreen extends Screen {
    private int page;
    EvCockpitCarRouteWeatherScreen(@NonNull CarContext c){super(c);}

    private static String eta(long seconds) {
        if(seconds<=0)return "—";
        SimpleDateFormat f=new SimpleDateFormat("dd.MM HH:mm",Locale.forLanguageTag("tr-TR"));
        f.setTimeZone(TimeZone.getTimeZone("Europe/Istanbul"));
        return f.format(new Date(seconds*1000));
    }

    @NonNull @Override public Template onGetTemplate() {
        ItemList.Builder rows=new ItemList.Builder();
        String text=getCarContext().getSharedPreferences("evcockpit_route_weather_v1",
            CarContext.MODE_PRIVATE).getString("packet","");
        JSONObject packet;
        try{packet=new JSONObject(text);}catch(Exception e){packet=null;}
        JSONArray cards=packet==null?null:packet.optJSONArray("cards");
        long updated=packet==null?0:packet.optLong("updatedAt",0);
        long age=System.currentTimeMillis()-updated;
        if(cards==null||cards.length()==0||age<0||age>3600000){
            rows.addItem(new Row.Builder()
                .setTitle("Güncel rota tahmini yok")
                .addText("Telefonda Yol Arkadaşım ile rota oluşturup hava verilerini aç.")
                .build());
            rows.addItem(new Row.Builder().setTitle("Kaynak")
                .addText("Open-Meteo · saatlik meteoroloji modeli; sensör verisi değil")
                .build());
        }else{
            rows.addItem(new Row.Builder().setTitle("Open-Meteo saatlik tahmin")
                .addText("Model yeniliği: "+(age/60000)+" dk · "
                    +cards.length()+" rota noktası")
                .build());
            final int pageCount=(cards.length()+3)/4;
            if(page>=pageCount)page=0;
            int end=Math.min(cards.length(),(page+1)*4);
            for(int i=page*4;i<end;i++){
                JSONObject card=cards.optJSONObject(i);
                if(card==null)continue;
                String label=card.optInt("percent",-1)==0?"Başlangıç":
                    card.optInt("percent",-1)==100?"Varış":
                    "Güzergâh %"+card.optInt("percent");
                label+=" · "+eta(card.optLong("arrivalTime",0));
                if(!card.optBoolean("available",false)){
                    rows.addItem(new Row.Builder().setTitle(label)
                        .addText("Bu noktada geçerli tahmin bulunamadı").build());
                    continue;
                }
                String value=Math.round(card.optDouble("temperature"))+"°C"+
                    " · yağış %"+Math.round(card.optDouble("rainProbability"))+
                    " · rüzgâr "+Math.round(card.optDouble("wind"))+" km/sa";
                JSONArray risk=card.optJSONArray("risks");
                String note="";
                if(risk!=null && risk.length()>0){
                    note="Model riski: "+risk.optString(0,"");
                    if(risk.length()>1)note+=" ve diğerleri";
                }else note="Saatlik model · yol durumu ölçümü değil";
                rows.addItem(new Row.Builder().setTitle(label)
                    .addText(value).addText(note).build());
            }
            if(pageCount>1){
                rows.addItem(new Row.Builder().setTitle("Diğer yol noktaları")
                    .addText("Sayfa "+(page+1)+"/"+pageCount)
                    .setOnClickListener(()->{
                        page=(page+1)%pageCount;
                        invalidate();
                    }).build());
            }
        }
        return new ListTemplate.Builder()
            .setTitle("EV Cockpit · Yol Boyunca Hava")
            .setHeaderAction(Action.BACK).setSingleList(rows.build()).build();
    }
}
