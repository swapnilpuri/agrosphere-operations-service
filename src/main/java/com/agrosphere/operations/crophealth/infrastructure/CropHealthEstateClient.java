package com.agrosphere.operations.crophealth.infrastructure;

import com.agrosphere.operations.application.Access;
import com.agrosphere.operations.crophealth.application.CropHealthPorts.References;
import com.agrosphere.operations.crophealth.domain.CropHealthRules.Context;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Fixed-origin authenticated HTTP with a whole-response deadline, 64 KiB cap and no redirects. */
@Component
public class CropHealthEstateClient implements References {
  private final Access access;private final URI base;private final Duration timeout;private final HttpClient client;
  private final ObjectMapper json=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  public CropHealthEstateClient(Access access,@Value("${operations.estate-url:http://localhost:8081}") String url,
      @Value("${operations.estate-timeout-seconds:3}") long seconds) {
    this.access=access;base=URI.create(url);
    if(!Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||seconds<1||seconds>30)
      throw new IllegalArgumentException("Invalid crop evidence Estate configuration");
    timeout=Duration.ofSeconds(seconds);client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
  }
  private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Canonical crop evidence metadata unavailable");}
  private JsonNode get(String path) {
    var request=HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("Authorization","Bearer "+access.authentication().getToken().getTokenValue())
        .header("X-Correlation-ID",Optional.ofNullable(org.slf4j.MDC.get("correlationId")).orElseGet(()->UUID.randomUUID().toString())).GET().build();
    var future=client.sendAsync(request,info->new BoundedBody());
    try{
      var response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
      if(response.statusCode()==401||response.statusCode()==403)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Canonical crop evidence validation denied");
      if(response.statusCode()==404)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Unknown canonical workforce reference");
      if(response.statusCode()!=200)throw unavailable();
      var value=json.readTree(response.body());if(value==null||!value.isObject())throw unavailable();return value;
    }catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw unavailable();}
    catch(TimeoutException|ExecutionException|tools.jackson.core.JacksonException e){future.cancel(true);throw unavailable();}
  }
  private String text(JsonNode n,String name){var v=n.path(name);if(!v.isTextual()||v.asText().isBlank())throw unavailable();return v.asText();}
  private UUID uuid(JsonNode n,String name){var raw=text(n,name);try{var id=UUID.fromString(raw);if(raw.length()!=36||!id.toString().equalsIgnoreCase(raw))throw unavailable();return id;}catch(IllegalArgumentException e){throw unavailable();}}
  @Override public Context activeBlock(UUID estate,UUID block) {
    var e=get("/api/v1/estates/"+estate);String es=text(e,"status");
    if(!Set.of("ACTIVE","INACTIVE").contains(es))throw unavailable();
    if(!uuid(e,"id").equals(estate)||!es.equals("ACTIVE"))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid/inactive canonical estate");
    ZoneId zone;try{zone=ZoneId.of(text(e,"timezone"));}catch(DateTimeException ex){throw unavailable();}
    var b=get("/api/v1/blocks/"+block);String bs=text(b,"status");
    if(!Set.of("ACTIVE","INACTIVE").contains(bs))throw unavailable();
    if(!uuid(b,"id").equals(block)||!uuid(b,"estateId").equals(estate)||!bs.equals("ACTIVE")||!text(b,"cropType").equals("RUBBER"))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid/inactive canonical block");
    return new Context(estate,block,zone);
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody(){return result;}
    @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
    @Override public void onNext(List<ByteBuffer> buffers){
      for(var buffer:buffers){if(bytes.size()+buffer.remaining()>65536){subscription.cancel();result.completeExceptionally(new IOException("Response exceeds cap"));return;}
        byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}
      subscription.request(1);
    }
    @Override public void onError(Throwable error){result.completeExceptionally(error);}
    @Override public void onComplete(){result.complete(bytes.toByteArray());}
  }
}
