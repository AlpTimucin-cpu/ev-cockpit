package me.aap.fermata.ev;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import org.json.JSONObject;
import java.util.Locale;

/** Driver-safe EPDK point-of-interest details. Never implies live stall status. */
final class EvCockpitCarStationDetailsScreen extends Screen {
    private final String name, operator, address, sockets;
    private final double distanceKm;
    private final String dataAge;

    EvCockpitCarStationDetailsScreen(@NonNull CarContext context, JSONObject station, String dataAge) {
        super(context);
        this.name=safe(station.optString("title"),"Şarj noktası",80);
        this.operator=safe(station.optString("operator"),"Bilinmiyor",80);
        this.address=safe(station.optString("address"),"Adres belirtilmemiş",120);
        this.sockets=safe(station.optString("sockets"),"Soket türü bilinmiyor",80);
        this.distanceKm=station.optDouble("distanceKm",Double.NaN);
        this.dataAge=safe(dataAge,"Güncellik bilinmiyor",60);
    }

    private static String safe(String value,String fallback,int limit) {
        if(value==null || value.trim().isEmpty())return fallback;
        String v=value.trim().replaceAll("[\\r\\n\\t]"," ");
        return v.length()>limit?v.substring(0,limit):v;
    }

    @NonNull @Override public Template onGetTemplate() {
        ItemList.Builder rows=new ItemList.Builder();
        rows.addItem(new Row.Builder().setTitle("Operatör").addText(operator).build());
        rows.addItem(new Row.Builder().setTitle("Soketler").addText(sockets).build());
        rows.addItem(new Row.Builder().setTitle("Adres").addText(address).build());
        rows.addItem(new Row.Builder().setTitle("Kuş uçuşu uzaklık").addText(
            Double.isFinite(distanceKm)&&distanceKm>=0?
                String.format(Locale.forLanguageTag("tr-TR"),"Yaklaşık %.1f km",distanceKm):
                "Güncel GPS verisi yok").build());
        rows.addItem(new Row.Builder().setTitle("EPDK kaydı")
            .addText(dataAge+" · canlı doluluk / fiyat bilinmiyor").build());
        return new ListTemplate.Builder()
            .setTitle(name)
            .setHeaderAction(Action.BACK)
            .setSingleList(rows.build())
            .build();
    }
}
