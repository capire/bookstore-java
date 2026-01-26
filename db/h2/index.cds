//
//  Add Author.age and .lifetime with a DB-specific function
//

using { AdminService } from '@capire/bookshop-java';

extend projection AdminService.Authors with {
  DATEDIFF('DAY', dateOfBirth, dateOfDeath) / 356 as age: Integer,
  SUBSTRING(dateOfBirth,1,4) || ' – ' || SUBSTRING(dateOfDeath,1,4) as lifetime : String
}
