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
import React from 'react'
import styled from 'styled-components'
import UnitsDropdown from './units-dropdown'
import TextField from '../../text-field'
import { ErrorComponent } from '../../../react-component/utils/validation'
import { relativeTimeError } from '../../../react-component/filter/filter-input/filter-date-inputs/filter-relative-time-input/relativeTimeHelper'

const Label = styled.div`
  font-weight: bolder;
`

const InputContainer = styled.div`
  margin-bottom: ${({ theme }) => theme.minimumSpacing};
  width: 100%;
`

const LastInput = styled(TextField)`
  width: 100%;
`

const getErrorState = (value, unit) => {
  let newValue = Number.parseFloat(value)
  return relativeTimeError({ last: newValue, unit })
    ? { error: true, message: 'value too large' }
    : { error: false }
}

const serialize = (last, unit) => ({ last, unit })

const RelativeTime = props => {
  return (
    <div className={props.className}>
      <InputContainer>
        <Label>Last</Label>
        <LastInput
          type="number"
          value={props.last}
          onChange={value => {
            props.onChange(
              serialize(value, props.unit),
              getErrorState(value, props.unit)
            )
          }}
        />
        <ErrorComponent errorState={props.errorState} />
      </InputContainer>
      <InputContainer>
        <Label>Units</Label>
        <UnitsDropdown
          value={props.unit}
          onChange={value => {
            props.onChange(
              serialize(props.last, value),
              getErrorState(props.last, value)
            )
          }}
        />
      </InputContainer>
    </div>
  )
}

export default RelativeTime