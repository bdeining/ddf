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
/*global define*/


define([
    'backbone',
    'marionette',
    'underscore',
    'jquery',
    './builder.hbs',
    'js/CustomElements',
    'component/property/property.collection.view',
    'component/loading/loading.view',
    'component/available-types/available-types.view',
    'component/dropdown/dropdown.view',
    'component/singletons/metacard-definitions'
], function (Backbone, Marionette, _, $, template, CustomElements, PropertyCollectionView, LoadingView, AvailableTypesView, DropdownView, metacardDefinitions) {

    let availableTypes;

    const ajaxCall = $.get({
        url: '/search/catalog/internal/builder/availabletypes'
    }).then((response) => {
        availableTypes = response;
    });

    return Marionette.LayoutView.extend({
            template: template,
            tagName: CustomElements.register('builder'),
            modelEvents: {
                'change:selectedAvailableType': 'handleSelectedAvailableType',
                'change:metacard': 'handleMetacard'
            },
            events: {
                'click .builder-edit': 'edit',
                'click .builder-save': 'save',
                'click .builder-cancel': 'cancel'
            },
            regions: {
                builderProperties: '> .builder-properties',
                builderAvailableType: '> .builder-select-available-type > .builder-select-available-type-dropdown'
            },
            initialize(options) {

                if (!availableTypes) {
                    const loadingview = new LoadingView();
                    ajaxCall.then(() => {
                        loadingview.remove();
                        this.model.set('availableTypes', availableTypes);
                        // TODO this seems like a race condition. If the ajax call finishes before the initialize method finishes and the DOM is built, then the call the handleAvailableTypes will fail
                        this.handleAvailableTypes();
                    });
                } else {
                    this.model.set('availableTypes', availableTypes);
                }

            },
            handleAvailableTypes() {

                const availableTypes = this.model.get('availableTypes');

                if(availableTypes && availableTypes.availabletypes && availableTypes.availabletypes.length == 1) {
                    this.filterInjected = true;
                    this.model.set('selectedAvailableType', this.model.get('availableTypes').availabletypes[0].metacardType);
                } else if(availableTypes && availableTypes.availabletypes && availableTypes.availabletypes.length > 1) {
                    this.filterInjected = false;
                    this.$el.addClass('is-selecting-available-types');
                } else {
                    this.filterInjected = false;
                    const allTypes = Object.keys(metacardDefinitions.metacardDefinitions)
                        .sort()
                        .reduce((accumulator, currentValue) => {
                            accumulator.availabletypes.push({ metacardType: currentValue });
                            return accumulator;
                        }, { availabletypes: [] });
                    this.model.set('availableTypes', allTypes);
                    this.$el.addClass('is-selecting-available-types');
                }

            },
            handleSelectedAvailableType() {
                this.$el.removeClass('is-selecting-available-types');

                const metacardDefinition = metacardDefinitions.metacardDefinitions[this.model.get('selectedAvailableType')];

                const nonInjectedAttributeNames = Object.keys(metacardDefinition)
                    .filter(attributeName => !metacardDefinitions.isHiddenType(attributeName))
                    .filter(attributeName => !metacardDefinition[attributeName].readOnly)
                    .filter(attributeName => !this.filterInjected || !metacardDefinition[attributeName].isInjected)
                    .filter(attributeName => attributeName !== "id");

                const propertyCollection = {
                    'metacard-type': this.model.get('selectedAvailableType')
                };

                nonInjectedAttributeNames.forEach(attribute => {
                    if(metacardDefinitions.enums[attribute]) {
                        if(metacardDefinition[attribute].multivalued) {
                            propertyCollection[attribute] = [ ];
                        } else {
                            propertyCollection[attribute] = metacardDefinitions.enums[attribute][0];
                        }
                    } else if (metacardDefinition[attribute].multivalued) {
                        propertyCollection[attribute] = [];
                    } else {
                        propertyCollection[attribute] = "";
                    }
                });

                this.model.set('metacard', propertyCollection);

            },
            handleMetacard() {
                this.builderProperties.show(PropertyCollectionView.generatePropertyCollectionView([this.model.get('metacard')]));
                this.builderProperties.currentView.turnOnLimitedWidth();
                this.builderProperties.currentView.$el.addClass("is-list");

            },
            onBeforeShow() {

                    this.builderAvailableType.show(DropdownView.createSimpleDropdown({
                                                  componentToShow: AvailableTypesView,
                                                  modelForComponent: this.model,
                                                  leftIcon: 'fa fa-ellipsis-v'
                                                }));

                    this.handleAvailableTypes();

            },
            edit() {
                        this.$el.addClass('is-editing');
                        this.builderProperties.currentView.turnOnEditing();
                        this.builderProperties.currentView.focus();
            },
            cancel() {
                        this.$el.removeClass('is-editing');
                        this.builderProperties.currentView.revert();
                        this.builderProperties.currentView.turnOffEditing();
            },
            save() {
                        this.$el.removeClass('is-editing');

                        const editedMetacard = this.builderProperties.currentView.toPropertyJSON([], []);

                        const props = editedMetacard.properties;
                        editedMetacard.properties = Object.keys(editedMetacard.properties)
                            .filter(attributeName => props[attributeName].length == 1 && props[attributeName][0] != "")
                            .reduce((accummulator, currentValue) => _.extend(accummulator, { [currentValue]: props[currentValue]}), {});

                        editedMetacard.properties['metacard-type'] = this.model.get('selectedAvailableType');

                        // TODO should we use a spinner?
                        $.ajax({
                            type: 'POST',
                            url: '/services/catalog/?transform=input-propertyjson',
                            data: JSON.stringify(editedMetacard),
                            contentType: 'application/json'
                        }).then((response, status, xhr) => {
                            this.options.handleNewMetacard(xhr.getResponseHeader('id'));
                            this.options.close();
                        });

                        this.builderProperties.currentView.turnOffEditing();
            }
    });
});    