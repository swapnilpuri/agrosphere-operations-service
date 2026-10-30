package com.agrosphere.operations.workforce.infrastructure;

import com.agrosphere.operations.application.Access;
import com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Uses the platform's independently validated JWT, without inheriting legacy role unions. */
@Component
public class JwtWorkforceIdentity implements com.agrosphere.operations.workforce.application.WorkforcePorts.Identity {
  private final Access access;
  public JwtWorkforceIdentity(Access access){this.access=access;}
  public Actor current() {
    var jwt=access.authentication().getToken();Object realm=jwt.getClaim("realm_access");
    if(!(realm instanceof Map<?,?> map)||!(map.get("roles") instanceof Collection<?> values)||values.stream().anyMatch(v->!(v instanceof String)))
      throw new Denied("Workforce role required");
    var roles=new HashSet<String>();values.forEach(v->roles.add((String)v));
    var estates=new HashSet<UUID>();Object claim=jwt.getClaim("estate_ids");
    if(!roles.contains("PLATFORM_ADMIN")) {
      if(!(claim instanceof Collection<?> ids)||ids.size()>1000)throw new Denied("Trusted workforce estate scope required");
      for(Object id:ids) {
        try{if(!(id instanceof String s)||s.length()!=36)throw new IllegalArgumentException();
          var parsed=UUID.fromString((String)id);if(!parsed.toString().equalsIgnoreCase((String)id))throw new IllegalArgumentException();estates.add(parsed);
        }catch(IllegalArgumentException e){throw new Denied("Invalid workforce estate scope");}
      }
    }
    try{return new Actor(jwt.getSubject(),roles,estates);}catch(Invalid e){throw new Denied("Valid workforce subject required");}
  }
}
