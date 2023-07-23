package com.polarbookshop.orderservice.order.domain;

import com.polarbookshop.orderservice.book.Book;
import com.polarbookshop.orderservice.book.BookClient;
import com.polarbookshop.orderservice.order.event.OrderAcceptedMessage;
import com.polarbookshop.orderservice.order.event.OrderDispatchedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class OrderService {
  private static final Logger log =
      LoggerFactory.getLogger(OrderService.class);
  private final StreamBridge streamBridge;
  private final BookClient bookClient;
  private final OrderRepository orderRepository;

  public OrderService(BookClient bookClient, OrderRepository orderRepository, StreamBridge streamBridge) {
    this.bookClient = bookClient;
    this.orderRepository = orderRepository;
    this.streamBridge = streamBridge;
  }

  public Flux<Order> getAllOrders() {
    return orderRepository.findAll();
  }

  @Transactional
  public Mono<Order> submitOrder(String isbn, int quantity) {
    return bookClient.getBookByIsbn(isbn)
        .map(book -> buildAcceptedOrder(book, quantity))
        .defaultIfEmpty(buildRejectedOrder(isbn, quantity))
        .flatMap(orderRepository::save)
        .doOnNext(this::publishOrderAcceptedEvent);
  }

  private static Order buildAcceptedOrder(Book book, int quantity) {
    return Order.of(book.isbn(), book.title() + " - " + book.author(),
        book.price(), quantity, OrderStatus.ACCEPTED);
  }

  public static Order buildRejectedOrder(String bookIsbn, int quantity) {
    return Order.of(bookIsbn, null, null, quantity, OrderStatus.REJECTED);
  }

  private void publishOrderAcceptedEvent(Order order) {
    if(!order.status().equals(OrderStatus.ACCEPTED)) {
      return;
    }
    var orderAcceptedMessage = new OrderAcceptedMessage(order.id());
    log.info("Sending order accepted event with id: {}", order.id());
    var result = streamBridge.send("acceptOrder-out-0", orderAcceptedMessage);
    log.info("Result of sending data for order with id {}: {}", order.id(), result);
  }

  public Flux<Order> consumeOrderDispatchedEvent(Flux<OrderDispatchedMessage> dispatchedMessageFlux) {
    return dispatchedMessageFlux
        .flatMap(dispatchedMessage -> orderRepository.findById(dispatchedMessage.orderId()))
        .map(this::buildDispatchedOrder)
        .flatMap(orderRepository::save);
  }

  private Order buildDispatchedOrder(Order order) {
    return new Order(
        order.id(),
        order.bookIsbn(),
        order.bookName(),
        order.bookPrice(),
        order.quantity(),
        OrderStatus.DISPATCHED,
        order.createdDate(),
        order.lastModifiedDate(),
        order.version()
    );
  }
}
