/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.catalog.ui.scheduling;

import java.util.stream.IntStream;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

public enum RepetitionTimeUnit {
  // To repeat each unit of time in a cron expression, fields will either need to be starred ("*")
  // or filled with the value of that field in the start date-time.
  // where "S" is the value of that field in the start date:
  // every minute: * * * * *
  // every hour:   S * * * *
  // every day:    S S * * *
  // every week:   S S * * S
  // every month:  S S S * *
  // every year:   S S S S *

  MINUTES(),
  HOURS(0),
  DAYS(0, 1),
  WEEKS(0, 1, 4),
  MONTHS(0, 1, 2),
  YEARS(0, 1, 2, 3);

  private int[] cronFieldsThatShouldBeFilled;

  RepetitionTimeUnit(int... cronFieldsThatShouldBeFilled) {
    this.cronFieldsThatShouldBeFilled = cronFieldsThatShouldBeFilled;
  }

  public boolean cronFieldShouldBeFilled(int index) {
    return IntStream.of(cronFieldsThatShouldBeFilled)
        .anyMatch(cronFieldIndex -> cronFieldIndex == index);
  }

  public String makeCronToRunEachUnit(DateTime start) {
    /*
       While the rest of this feature is designed around UTC DateTimes to be as neutral as possible, it would appear that
       the Ignite Scheduler requires the cron string it uses to trigger execution to be expressed in the timezone of the executing machine
       So, convert the start time to whatever timezone `DateTimeZone.getDefault()` returns

       From the API Docs for DateTimeZone.getDefault():
       The default time zone is derived from the system property user.timezone. If that is null or is not a valid identifier,
       then the value of the JDK TimeZone default is converted. If that fails, UTC is used.
       NOTE: If the java.util.TimeZone default is updated after calling this method, then the change will not be picked up here.

       TODO: When we get clustering up and running, will clustered machines be sharing schedule information across timezones? Can we get around this with an Ignite config change instead?
    */
    DateTime localStart = start.toDateTime(DateTimeZone.getDefault());
    final String[] cronFields = new String[5];
    final int[] startValues =
        new int[] {
          localStart.getMinuteOfHour(),
          localStart.getHourOfDay(),
          localStart.getDayOfMonth(),
          localStart.getMonthOfYear(),
          // Joda's day of the week value := 1-7 Monday-Sunday;
          // cron's day of the week value := 0-6 Sunday-Saturday
          localStart.getDayOfWeek() % 7
        };
    for (int i = 0; i < 5; i++) {
      if (cronFieldShouldBeFilled(i)) {
        cronFields[i] = String.valueOf(startValues[i]);
      } else {
        cronFields[i] = "*";
      }
    }

    return String.join(" ", cronFields);
  }
}
