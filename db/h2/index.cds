//
//  Add Author.age and .lifetime with a DB-specific function
//

using { AdminService } from '@capire/bookshop';

extend projection AdminService.Authors with {
  DATEDIFF('DAY', dateOfBirth, dateOfDeath) / 356 as age: Integer,
  SUBSTRING(dateOfBirth,0,4) || ' – ' || SUBSTRING(dateOfDeath,0,4) as lifetime : String
}
