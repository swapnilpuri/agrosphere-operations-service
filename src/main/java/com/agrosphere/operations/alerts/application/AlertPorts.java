package com.agrosphere.operations.alerts.application;

import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.alerts.domain.AlertRules.Actor;
import com.agrosphere.operations.alerts.domain.CropHealthAlert;
import com.agrosphere.operations.alerts.domain.CropHealthAlert.*;
import java.time.*;
import java.util.*;

/** AN-T02 boundaries only. No bean/HTTP/JDBC/consumer/migration registered. */
public final class AlertPorts {
  private AlertPorts() {}
  public interface IdentityProvider { Actor current(); }
  public record Filter(UUID estateId,UUID blockId,State state,Severity severity,boolean unreadOnly,boolean unacknowledgedOnly,int page,int size) {
    public Filter { Objects.requireNonNull(estateId);state=state==null?State.ACTIVE:state;pageBounds(page,size); }
  }
  public static void pageBounds(int page,int size) { require(page>=0 && size>=1 && size<=100 && (long)page*size<=10000,"Invalid alert page"); }
  public record View(CropHealthAlert alert,Identity viewer,ReadMarker ownRead) {
    public View {
      Objects.requireNonNull(alert);Objects.requireNonNull(viewer);
      if(ownRead!=null) { conflict(ownRead.actor().equals(viewer),"Foreign read marker");alert.unread(ownRead); }
    }
    public boolean unreadForMe() { return alert.unread(ownRead); }
  }
  public record Page<T>(List<T> content,int page,int size,long totalElements,Instant generatedAt) {
    public Page {
      content=List.copyOf(content);pageBounds(page,size);precise(generatedAt);
      require(content.size()<=size && totalElements>=content.size(),"Invalid page cardinality");
    }
  }
  public record Counts(long activeTotal,long activeUnreadForMe,long activeUnacknowledged,Instant generatedAt) {
    public Counts {
      precise(generatedAt);require(activeTotal>=0 && activeUnreadForMe>=0 && activeUnacknowledged>=0
          && activeUnreadForMe<=activeTotal && activeUnacknowledged<=activeTotal,"Invalid scoped counts");
    }
  }
  public interface Queries {
    /** Implementations scope SQL BEFORE data/count/history, use one consistent snapshot per result. */
    View alert(UUID id,Actor current);
    Page<View> alerts(Filter filter,Actor current);
    Counts counts(UUID estateId,Actor current);
    Page<Transition> history(UUID id,Actor current,int page,int size);
  }
  public interface RuleChanges {
    /** Existing source transaction; after source roots, lock alert. No broker/HTTP/backfill. */
    void acceptedIncidentRevision(Source before,Source after,Instant now,UUID correlationId);
  }
  public interface LockedAlerts {
    CropHealthAlert lock(UUID id,Actor current);
    ReadMarker ownRead(UUID alertId,Identity current);
    void saveAlert(CropHealthAlert changed,long expectedVersion);
    void saveOwnRead(ReadMarker marker);
    void appendTransition(Transition transition);
    void appendAcknowledgement(Acknowledgement acknowledgement);
  }
  public record Receipt(Identity actor,UUID key,UUID estateId,Permission permission,String path,String normalizedHash,String originalBody) {
    public Receipt {
      Objects.requireNonNull(actor);Objects.requireNonNull(key);Objects.requireNonNull(estateId);Objects.requireNonNull(permission);Objects.requireNonNull(originalBody);
      require(permission==Permission.READ||permission==Permission.MANAGER,"Invalid receipt permission");
      require(path!=null && path.matches("/api/v1/alerts/[0-9a-f-]{36}/(?:read|acknowledgements)"),"Invalid alert target");
      String[] segments=path.split("/");
      try { require(UUID.fromString(segments[4]).toString().equals(segments[4]),"Noncanonical alert target"); }
      catch(IllegalArgumentException e) { throw new Invalid("Invalid alert target"); }
      require(path.endsWith("/read")==(permission==Permission.READ),"Receipt permission mismatch");
      require(normalizedHash!=null && normalizedHash.matches("[0-9a-f]{64}"),"Invalid normalized hash");
    }
    /** Called BEFORE returning original body; raw token/username is never a receipt identity. */
    public void authorizeReplay(Actor current,UUID requestedKey,String requestedPath,String requestedHash) {
      current.authorize(estateId,permission);
      if(!current.identity().equals(actor)) throw new Denied("Receipt owner denied");
      conflict(key.equals(requestedKey) && path.equals(requestedPath) && normalizedHash.equals(requestedHash),"Command key reused");
    }
  }
  @FunctionalInterface public interface Work { Receipt execute(LockedAlerts store); }
  public interface Transactions {
    /** Reauthorize first; reserve actor/key; original success bypasses work. Same transaction owns
     * marker/ack/transition/receipt. Lock alert only; never acquire a source lock afterward. */
    Receipt execute(Actor actor,UUID key,UUID alertId,Permission permission,String path,String normalizedHash,Work work);
  }
}
