/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/

module.exports = {
  getFontSize(percentage) {
    return (percentage * 16) / 100
  },
  getZoomScale(fontSize) {
    let value = 100 * (fontSize / 16)
    return Math.floor(this.getCalculatedZoomScale(value, 62, 200, 1, 100))
  },
  getCalculatedZoomScale(value, oldMin, oldMax, newMin, newMax) {
    let percent = (value - oldMin) / (oldMax - oldMin)
    let adjustedValue = percent * (newMax - newMin) + newMin
    return adjustedValue
  },
}
