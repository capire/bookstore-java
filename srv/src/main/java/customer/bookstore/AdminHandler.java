package customer.bookstore;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sap.cds.Result;
import com.sap.cds.ql.Select;
import com.sap.cds.reflect.CdsEntity;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.cds.CdsCreateEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.draft.DraftNewEventContext;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.adminservice.AdminService_;
import cds.gen.adminservice.Authors_;
import cds.gen.adminservice.Books_;

@Component
@ServiceName(AdminService_.CDS_NAME)
public class AdminHandler implements EventHandler {
  
  private final PersistenceService db;

  public AdminHandler(PersistenceService db) {
    this.db = db;
  }

  @Before(
    event = { DraftService.EVENT_DRAFT_NEW, CqnService.EVENT_CREATE },
    entity = { Books_.CDS_NAME, Authors_.CDS_NAME }
  )
  void genId(EventContext ctx) {
    CdsEntity target = ctx.getTarget();
    Result result = db.run(Select.from(target).columns(b -> b.get("ID").max().as("id")));
    Integer id = (Integer) result.single().get("id");

    List<Map<String, Object>> entries = switch(ctx) {
      case CdsCreateEventContext createCtx -> createCtx.getCqn().entries();
      case DraftNewEventContext newCtx -> newCtx.getCqn().entries();
      default -> List.of();
    };
    entries.forEach(m -> m.put("ID", id != null ? id + 4 : 0 )); // Note: that is not safe! ok for this sample only.
  }
}
