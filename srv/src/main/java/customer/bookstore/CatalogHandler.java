package customer.bookstore;

import org.springframework.stereotype.Component;

import com.sap.cds.services.ServiceException;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnUpdate;
import com.sap.cds.ql.cqn.CqnSelect;

import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.On;

import cds.gen.catalogservice.Books;
import cds.gen.catalogservice.Books_;
import cds.gen.catalogservice.CatalogService_;
import cds.gen.catalogservice.Currencies;
import cds.gen.catalogservice.ListOfBooks;
import cds.gen.catalogservice.SubmitOrderContext;
import cds.gen.sap.capire.orders.api.ordersservice.OrderChanged_;
import cds.gen.sap.capire.orders.api.ordersservice.Orders;
import cds.gen.sap.capire.orders.api.ordersservice.OrdersNoDraft_;
import cds.gen.sap.capire.orders.api.ordersservice.OrdersService_;
import cds.gen.sap.capire.reviews.api.reviewsservice.AverageRatingsChanged_;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;

// messages
import com.sap.cds.services.messaging.MessagingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.text.SimpleDateFormat;
import com.sap.cds.services.messaging.TopicMessageEventContext;
import com.sap.cds.services.outbox.OutboxService;


@Component
@ServiceName(CatalogService_.CDS_NAME)
public class CatalogHandler implements EventHandler {

  @Autowired
  @Qualifier("samples-messaging")
  private MessagingService messagingService;

  @Autowired
  @Qualifier(OrdersService_.CDS_NAME)
  CqnService ordersService;

  CqnService ordersServiceOutboxed;

  private final PersistenceService persistenceService;

  public CatalogHandler(PersistenceService persistenceService) {
    this.persistenceService = persistenceService;
  }

  @Autowired
  public void setRuntime(CdsRuntime runtime) {
    ordersServiceOutboxed = runtime.getServiceCatalog()
      .getService(OutboxService.class, OutboxService.PERSISTENT_ORDERED_NAME)
      .outboxed(ordersService);
  }

  @After(event = CqnService.EVENT_READ)
  public void AfterReadListOfBooks(Stream<ListOfBooks> books) {
    books.forEach(book -> {
      if(book.getStock()>111)
        book.setTitle(book.getTitle()+" -- 11% discount!");
    });
  }

  @On(event = SubmitOrderContext.CDS_NAME)
  public SubmitOrderContext.ReturnType submitOrder(SubmitOrderContext context) {
    Integer quantity = context.getQuantity();
    if(quantity<=0)
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, "quantity has to be 1 or more").messageTarget("submitOrder");
    Integer booksLeft = updateBookQuantity(context.getBook(), -context.getQuantity());
    SubmitOrderContext.ReturnType response = SubmitOrderContext.ReturnType.create();
    response.setStock(booksLeft);
    return response;
  }

  @After(event = SubmitOrderContext.CDS_NAME)
  public void submitOrderAfter(SubmitOrderContext context) {
    CqnSelect selectBook = Select.from(CatalogService_.BOOKS)
      .columns(c -> c.title(),
              c -> c.price(),
            c -> c.currency().code())
      .where(b -> b.get("ID").eq(context.getBook().toString()));
    Row bookDetails = persistenceService.run(selectBook).single();
    var items = List.of(Map.of(Orders.Items.PRODUCT_ID, context.getBook().toString(),
      Orders.Items.QUANTITY,  context.getQuantity(),
      Orders.Items.TITLE,  bookDetails.get(Books.TITLE),
      Orders.Items.PRICE,  bookDetails.get(Books.PRICE)
    ));
    String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new java.util.Date());
    var order = Map.of(Orders.ORDER_NO, "Order at "+now,
      Orders.BUYER, context.getUserInfo().getName(),
      Orders.CURRENCY_CODE, bookDetails.get(Currencies.CODE),
      Orders.ITEMS, items);
    ordersServiceOutboxed.run(Insert.into(OrdersNoDraft_.CDS_NAME).entry(order));
  }

  @On(service = "samples-messaging", event = OrderChanged_.CDS_NAME)
  public void receiveOrderChanged(TopicMessageEventContext context) {
    try {
      Map<String, Object> payload = context.getDataMap();
      Integer productId = Integer.parseInt((String)payload.get("product")); 
      updateBookQuantity(productId, (Integer) payload.get("deltaQuantity"));
    } catch(Exception e) {
      System.out.println("receiveOrderChanged exception: "+e);
    }
  }

  @On(service = "samples-messaging", event = AverageRatingsChanged_.CDS_NAME)
  public void receiveReviewedMessage(TopicMessageEventContext context) {
    try {
      Map<String, Object> payload = context.getDataMap();
      String subject = (String) payload.get("subject");
      Integer count = (Integer) payload.get("count");
      Integer rating = (Integer) payload.get("rating");

      Map<String, Object> data = new HashMap<>();
      data.put(Books.RATING, rating);
      data.put(Books.REVIEWS, count);

      CqnUpdate update = Update.entity(Books_.CDS_NAME)
        .byId(subject)
        .data(data);

      Result updateResult = persistenceService.run(update);
      if(updateResult.rowCount()!=1)
        throw new ServiceException(ErrorStatuses.SERVER_ERROR, "Failed to update the book rating");
    } catch(Exception e) {
      System.out.println("receiveReviewedMessage exception: "+e);
    }
  }

  private Integer updateBookQuantity(Integer bookId, Integer deltaQuantity) {
    Result found = persistenceService.run(Select
      .from(Books_.CDS_NAME)
      .where(b -> b.get("ID").eq(bookId)));
    Books book = found.single(Books.class);
    Integer inStock = book.getStock();

    Integer booksLeft = inStock+deltaQuantity;
    if(booksLeft<0)
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Book("+bookId+") " + deltaQuantity + " exceeds stock").messageTarget("submitOrder");

    CqnUpdate update = Update.entity(Books_.CDS_NAME)
      .byId(book.getId())
      .set(Books.STOCK, CQL.get(Books.STOCK).plus(deltaQuantity));

    Result updateResult = persistenceService.run(update);
    if(updateResult.rowCount()!=1)
      throw new ServiceException(ErrorStatuses.SERVER_ERROR, "Failed to update the book quantity");
    return booksLeft;
  }
}
