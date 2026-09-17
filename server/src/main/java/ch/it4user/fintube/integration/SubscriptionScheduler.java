package ch.it4user.fintube.integration;
import ch.it4user.fintube.core.Database; import org.springframework.scheduling.annotation.Scheduled; import org.springframework.stereotype.Component; import java.sql.*;
/** A failed channel is isolated and one API sync serves all its subscribers. */
@Component public class SubscriptionScheduler {final Database db;final YouTubeSyncService sync;SubscriptionScheduler(Database d,YouTubeSyncService s){db=d;sync=s;}
 @Scheduled(fixedDelayString="PT15M") void syncDue(){
   try(Connection c=db.open();PreparedStatement p=c.prepareStatement(
       "SELECT c.channel_id FROM youtube_channels c JOIN youtube_subscriptions s ON s.channel_id=c.channel_id "
           + "WHERE s.enabled=1 AND (c.last_sync_at IS NULL OR c.last_sync_at<?) GROUP BY c.channel_id")){
     int mins;
     try { mins=Math.max(1,Integer.parseInt(db.settings(false).getOrDefault("subscription_sync_minutes","60"))); }
     catch(Exception e) { mins=60; }
     p.setString(1,java.time.Instant.now().minusSeconds(mins*60L).toString());
     ResultSet r=p.executeQuery();
     while(r.next()) try{sync.syncChannel(r.getString(1));}catch(Exception ignored){/* channel failure is isolated */}
   }catch(Exception ignored){/* scheduler must remain alive */}
 }
}
