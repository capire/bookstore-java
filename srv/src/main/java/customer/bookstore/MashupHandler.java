package customer.bookstore;

import org.springframework.stereotype.Component;

import com.sap.cds.services.ServiceException;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.Result;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnUpdate;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.On;

import cds.gen.catalogservice.Books;
import cds.gen.catalogservice.Books_;
import cds.gen.catalogservice.CatalogService_;
import cds.gen.catalogservice.SubmitOrderContext;
import cds.gen.sap.capire.orders.api.ordersservice.OrderChanged;
import cds.gen.sap.capire.orders.api.ordersservice.OrderChangedContext;
import cds.gen.sap.capire.orders.api.ordersservice.Orders;
import cds.gen.sap.capire.orders.api.ordersservice.OrdersNoDraft_;
import cds.gen.sap.capire.orders.api.ordersservice.OrdersService;
import cds.gen.sap.capire.orders.api.ordersservice.OrdersService_;
import cds.gen.sap.capire.reviews.api.reviewsservice.AverageRatingsChanged_;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
// messages
import com.sap.cds.services.messaging.MessagingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.text.SimpleDateFormat;
import com.sap.cds.services.messaging.TopicMessageEventContext;
import com.sap.cds.services.outbox.OutboxService;

@Component
@ServiceName(CatalogService_.CDS_NAME)
public class MashupHandler implements EventHandler {

  @Autowired
  @Qualifier("samples-messaging")
  private MessagingService messagingService;

  @Autowired
  OrdersService ordersService;

  @Autowired
  @Qualifier(OutboxService.PERSISTENT_ORDERED_NAME)
  OutboxService outboxService;

  @Autowired
  CatalogHandler catalogHandler;

  private final PersistenceService persistenceService;

  public MashupHandler(PersistenceService persistenceService) {
    this.persistenceService = persistenceService;
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

  @On(service = OrdersService_.CDS_NAME)
  private void onOrderChanged(OrderChangedContext context) {
    try {
      OrderChanged orderChaged = context.getData();
      Integer productId = Integer.parseInt(orderChaged.getProduct());
      Integer deltaQuantity = orderChaged.getDeltaQuantity();
      catalogHandler.updateBookQuantity(productId, deltaQuantity);
    } catch(Exception e) {
      System.out.println("onOrderChanged exception: "+e);
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

}
