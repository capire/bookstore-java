package customer.bookstore;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.adminservice.AdminService;
import cds.gen.adminservice.AdminService_;
import cds.gen.adminservice.Authors;
import cds.gen.adminservice.AuthorsCreateAuthorDraftContext;
import cds.gen.adminservice.Authors_;
import cds.gen.adminservice.Books;
import cds.gen.adminservice.BooksCreateBookDraftContext;
import cds.gen.adminservice.Books_;

@Component
@ServiceName(AdminService_.CDS_NAME)
public class AdminHandler implements EventHandler {
  
  private final AdminService.Draft adminServiceDraft;
  private final AdminService adminService;

  public AdminHandler(AdminService adminService, AdminService.Draft adminServiceDraft) {
    this.adminService = adminService;
    this.adminServiceDraft = adminServiceDraft;
  }

  @On(entity = Books_.CDS_NAME)
  void createBookDraft (BooksCreateBookDraftContext context) {
    Result rmax = adminService.run(Select.from(Books_.CDS_NAME)
      .columns(b -> CQL.max(b.get("ID")).as("max"))
    );
    Integer newId = 1;
    try {
      List<Row> r = rmax.list();
      for (int i = 0; i < r.size(); i++) {
        Integer val = (Integer) r.get(i).get("max");
        if(val!=null && newId<=val) newId=val+1;
      }
    } catch (Exception e) {
      System.out.println(e);
    }
    Books book = Books.create();
    book.setId(newId);
    Books result = adminServiceDraft.newDraft(Insert.into(Books_.CDS_NAME).entry(book)).single(Books.class);
    context.setResult(result);
  }

  @On(entity = Authors_.CDS_NAME)
  Authors createAuthorDraft(AuthorsCreateAuthorDraftContext context) {
    Result rmax = adminService.run(Select.from(Authors_.CDS_NAME)
      .columns(b -> CQL.max(b.get("ID")).as("max"))
    );
    Integer newId = 1;
    try {
      List<Row> r = rmax.list();
      for (int i = 0; i < r.size(); i++) {
        Integer val = (Integer) r.get(i).get("max");
        if(val!=null && newId<=val) newId=val+1;
      }
    } catch (Exception e) {
      System.out.println(e);
    }
    Authors author = Authors.create();
    author.setId(newId);
    return adminServiceDraft.newDraft(Insert.into(Authors_.class).entry(author)).single(Authors.class);
  }
}
