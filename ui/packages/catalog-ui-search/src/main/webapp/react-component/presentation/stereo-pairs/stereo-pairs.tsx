/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
import * as React from 'react'
import * as Backbone from 'backbone'
const Marionette = require('backbone.marionette')
import { hot } from 'react-hot-loader'

import MarionetteRegionContainer from '../../container/marionette-region-container'
import styled from '../../styles/styled-components'

const lightboxInstance = require('component/lightbox/lightbox.view.instance')
const InspectorView = require('component/visualization/inspector/inspector.view')
const SelectionInterfaceModel = require('component/selection-interface/selection-interface.model.js')
const Query = require('js/model/Query')
const cql = require('js/cql')
const CustomElements = require('js/CustomElements')

const closeDropdown = ($el: JQuery) =>
  $el.trigger('closeDropdown.' + CustomElements.getNamespace())

type Path = (string | number)[]

const getIn = (model: any, path: Path): any =>
  path.reduce((v, k) => {
    if (v === undefined) return
    if (typeof k === 'string') {
      if (typeof v.get === 'function') return v.get(k)
      if (typeof v === 'object') return v[k]
    }
    if (typeof k === 'number') {
      if (typeof v.at === 'function') return v.at(k)
      if (Array.isArray(v)) return v[k]
    }
  }, model)

export const getStereoPairId = (result: Backbone.Model): string | undefined =>
  getIn(result, ['metacard', 'properties', 'ext.stereo-image-id', 0])

export const hasStereoPair = (result: Backbone.Model): boolean =>
  getStereoPairId(result) !== undefined

const ActionItem = styled.div`
  cursor: pointer;
  padding: 0 10px;
`

const InteractionText = styled.span`
  height: ${props => props.theme.minimumButtonSize};
  line-height: ${props => props.theme.minimumButtonSize};
`

const InteractionIcon = styled.button`
  display: inline-block;
  text-align: center;
  vertical-align: top;
  width: ${props => props.theme.minimumButtonSize};
  height: ${props => props.theme.minimumButtonSize};
  line-height: ${props => props.theme.minimumButtonSize};
`

const Actions = Marionette.LayoutView.extend({
  onInspector() {
    this.options.onClose()
    closeDropdown(this.$el)
    lightboxInstance.model.updateTitle('Stereo Pairs - Inspector')
    lightboxInstance.model.open()
    const selectionInterface = new SelectionInterfaceModel()
    selectionInterface.addSelectedResult(this.model.get('a'))
    selectionInterface.addSelectedResult(this.model.get('b'))
    lightboxInstance.lightboxContent.show(
      new InspectorView({ selectionInterface })
    )
  },
  template() {
    return (
      <div>
        <ActionItem onClick={this.onInspector.bind(this)}>
          <InteractionIcon className="fa fa-expand" />
          <InteractionText>Open in Inspector</InteractionText>
        </ActionItem>
      </div>
    )
  },
})

const Button = styled.button`
  width: ${props => props.theme.minimumButtonSize};
  height: ${props => props.theme.minimumButtonSize};
`

const Title = styled.span`
  display: inline-block;
  font-size: ${props => props.theme.mediumFontSize};
  line-height: ${props => props.theme.minimumButtonSize};
`

const Container = styled.div`
  box-sizing: border-box;
  padding: 0 ${props => props.theme.mediumSpacing};
`

const Row = styled.div`
  max-width: 800px;
`

const Column = styled<{ width: number }, 'div'>('div')`
  width: ${props => props.width}%;
  display: inline-block;
`

type LabelProps = {
  text?: string
  children: React.ReactChildren
  className: string
}

const Label = styled<LabelProps, any>(
  ({ text, children, className }: LabelProps) => {
    return <div className={className}>{children || text}</div>
  }
)`
  line-height: ${props => props.theme.minimumButtonSize};
  font-size: ${props => props.theme.mediumFontSize};
  overflow: hidden;
  text-overflow: ellipsis;
`

type ImageProps = {
  src: string
}

const Image = ({ src }: ImageProps) => (
  <img style={{ width: '100%' }} src={src} />
)

const Center = styled(({ className, children }: any) => (
  <div className={className}>
    <div>{children}</div>
  </div>
))`
  position: relative;
  width: 400px;
  height: 400px;
  div {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
  }
  span {
    font-size: 120px;
  }
`

type PreviewProps = {
  pair: {
    thumbnail: string
    title: string
  }
  notFound?: boolean
}

const Preview = ({ pair, notFound }: PreviewProps) => {
  if (notFound) {
    return <Center>Not Found</Center>
  }
  if (pair === null) {
    return (
      <Center>
        <span className="fa fa-refresh fa-spin" />
      </Center>
    )
  }
  return (
    <React.Fragment>
      <Image src={pair.thumbnail} />
      <Label text={pair.title} />
    </React.Fragment>
  )
}

type SelectionInterface = {
  getActiveSearchResults(): Backbone.Collection<Backbone.Model>
}

export type Options = {
  model: Backbone.Model
  selectionInterface: SelectionInterface
}

const altId = 'ext.alternate-identifier-value'

const StereoPairsView = Marionette.LayoutView.extend({
  initialize(options: Options) {
    const id = getStereoPairId(options.model)
    const results = options.selectionInterface.getActiveSearchResults()
    const found = results.find(model =>
      (getIn(model, ['metacard', 'properties', altId]) || []).includes(id)
    )
    this.model = new Backbone.Model({ a: this.options.model, b: found })
    if (found === undefined) {
      this.fetchMetacard(id)
    }
    this.listenTo(this.model, 'change', this.render)
  },
  fetchMetacard(id: string) {
    const query = new Query.Model({
      cql: cql.write({
        type: 'ILIKE',
        value: id,
        property: `"${altId}"`,
      }),
      federation: 'enterprise',
    })
    query.startSearch()
    this.listenTo(query.get('result'), 'sync', (model: Backbone.Model) => {
      const b = getIn(model, ['queuedResults', 0])
      if (b !== undefined) {
        this.model.set('b', b)
      } else {
        this.model.set('not-found', true)
      }
    })
    this.query = query
  },
  onDestroy() {
    if (this.query !== undefined) {
      this.query.cancelCurrentSearches()
    }
  },
  behaviors() {
    return {
      button: {},
      dropdown: {
        dropdowns: [
          {
            selector: '.result-actions',
            view: Actions,
            viewOptions: () => ({
              onClose: () => {
                closeDropdown(this.$el)
              },
              model: this.model,
            }),
          },
        ],
      },
    }
  },
  template() {
    const a = getIn(this.model, ['a', 'metacard', 'properties'])
    const b = getIn(this.model, ['b', 'metacard', 'properties'])
    const notFound = getIn(this.model, ['not-found'])
    return (
      <Container>
        <Title>Stereo Pairs</Title>
        <Button
          className="result-actions is-button"
          title="Provides a list of actions to take on the stereo pair."
          data-help="Provides a list of actions to take on the stereo pair."
        >
          <span className="fa fa-ellipsis-v" />
        </Button>
        <Row>
          <Column width={49}>
            <Preview pair={a !== undefined ? a.toJSON() : null} />
          </Column>
          <Column width={2} />
          <Column width={49}>
            <Preview
              notFound={notFound}
              pair={b !== undefined ? b.toJSON() : null}
            />
          </Column>
        </Row>
      </Container>
    )
  },
})

const Component = (options: Options) => (
  <MarionetteRegionContainer view={StereoPairsView} viewOptions={options} />
)

export default hot(module)(Component)
