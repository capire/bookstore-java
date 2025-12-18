////////////////////////////////////////////////////////////////////////////
//
//    Enhancing bookshop with Reviews and Orders provided through
//    respective reuse packages and services
//


//
//  Extend Books with access to Reviews and average ratings
//
using { sap.capire.reviews.api.ReviewsService as reviews } from '@capire/reviews-java';
using { sap.capire.bookshop.Books } from '@capire/bookshop-java';
using { sap.capire.reviews.Reviews } from '@capire/reviews-java';
extend Books with {
  rating  : type of reviews.AverageRatings:rating; // average rating
  reviews : Integer @title : '{i18n>NumberOfReviews}';
}

//
//  Extend Orders with Books as Products
//
using { sap.capire.orders.Orders } from '@capire/orders-java';
extend Orders:Items with {
  book : Association to Books on product.ID = book.ID
}

// Ensure models from all imported packages are loaded
using from '@capire/bookstore-java/app/services';
using from '@capire/orders-java/app/fiori';
using from '@capire/data-viewer';
using from '@capire/common';
