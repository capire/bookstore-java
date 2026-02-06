package customer.bookstore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sap.cds.services.ServiceException;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.Result;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.ql.cqn.CqnUpdate;

import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.On;

import cds.gen.catalogservice.Books;
import cds.gen.catalogservice.Books_;
import cds.gen.catalogservice.CatalogService_;
import cds.gen.catalogservice.ListOfBooks;
import cds.gen.catalogservice.SubmitOrderContext;

import java.util.stream.Stream;

/**
 * 1:1 copy of bookshop CatalogHandler
 */
@Component
@ServiceName(CatalogService_.CDS_NAME)
public class CatalogHandler implements EventHandler {

  @Autowired
  PersistenceService persistenceService;
  
  public CatalogHandler() {
  }

  @After(event = CqnService.EVENT_READ)
  public void afterReadListOfBooks(Stream<ListOfBooks> books) {
    books.forEach(book -> {
      if (book.getStock() > 111)
        book.setTitle(book.getTitle() + " -- 11% discount!");
    });
  }

  @On
  public void submitOrder(SubmitOrderContext context) {
    Integer quantity = context.getQuantity();
    if (quantity <= 0)
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, "quantity has to be 1 or more").messageTarget("submitOrder");
    updateBookQuantity(context.getBook(), quantity);
    context.setCompleted();
  }

  protected void updateBookQuantity(Integer bookId, Integer quantity) {
    CqnUpdate update = Update.entity(Books_.class)
      .set(Books.STOCK, CQL.get(Books.STOCK).minus(quantity))
      .where(b -> b.get(Books.ID).eq(bookId)
      .and(b.get(Books.STOCK).ge(quantity)));
    Result updateResult = persistenceService.run(update);
    if (updateResult.rowCount() == 0) {
      CqnSelect select = Select.from(Books_.CDS_NAME).byId(bookId); 
      Result selectResult = persistenceService.run(select);
      if (selectResult.rowCount() == 0)
        throw new ServiceException(ErrorStatuses.CONFLICT, "Book not found");
      throw new ServiceException(ErrorStatuses.CONFLICT, "Quantity exceeds stock");
    }
  }

}