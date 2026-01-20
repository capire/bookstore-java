package customer.bookstore;

import org.springframework.stereotype.Component;

import com.sap.cds.services.ServiceException;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.CQL;
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
import cds.gen.sap.capire.orders.api.ordersservice.OrdersService;
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
  OrdersService ordersService;

  @Autowired
  @Qualifier(OutboxService.PERSISTENT_ORDERED_NAME)
  OutboxService outboxService;

  private final PersistenceService persistenceService;

  public CatalogHandler(PersistenceService persistenceService) {
    this.persistenceService = persistenceService;
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

  @After
  public void submitOrderAfter(SubmitOrderContext context) {
    var selectBook = Select.from(Books_.class)
      .columns(c -> c.title(),
               c -> c.price(),
               c -> c.currency().code())
      .where(b -> b.ID().eq(context.getBook()));
    var bookDetails = persistenceService.run(selectBook).single();
    Orders.Items item = Orders.Items.create();
    item.setProductId(context.getBook().toString());
    item.setTitle(bookDetails.getTitle());
    item.setPrice(bookDetails.getPrice().doubleValue());
    item.setQuantity(context.getQuantity());

    Orders order = Orders.create();
    String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new java.util.Date());
    order.setOrderNo("Order at " + now);
    order.setBuyer(context.getUserInfo().getName());
    order.setCurrencyCode(bookDetails.getCurrencyCode());
    order.setItems(List.of(item));

    OrdersService outboxed = outboxService.outboxed(ordersService);
    outboxed.run(Insert.into(OrdersNoDraft_.CDS_NAME).entry(order));
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

  private void updateBookQuantity(Integer bookId, Integer quantity) {
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
