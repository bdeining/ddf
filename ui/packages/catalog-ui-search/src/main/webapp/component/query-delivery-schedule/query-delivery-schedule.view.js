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
/*global define, setTimeout*/
define([
    'marionette',
    'backbone',
    'js/store',
    'component/singletons/user-instance',
    'js/CustomElements',
    './query-delivery-schedule.hbs',
    'component/property/property.view',
    'component/property/property'
], function(Marionette, Backbone, store, user, CustomElements, template, PropertyView, Property) {
    
    return Marionette.LayoutView.extend({
        template: template,
        tagName: CustomElements.register('query-delivery-schedule'),
        regions: {
            deliverySchedule: '.enable-delivery-scheduling',
            scheduleProperties: '.delivery-schedule-properties',
            timePicker: '.time-picker',
            deliveryPicker: '.delivery-method-picker'
        },
        initialize() {
        },
        onBeforeShow() {
            this.turnOffEditing();
            this.setupRegions();
            this.listenTo(this.deliverySchedule.currentView.model, 'change:value', this.handleSchedulingDeliveryValue);
            this.turnOnEditing();
        },
        setupRegions() {
            this.deliverySchedule.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('deliveryScheduled')],
                    id: 'Delivery Schedule',
                    radio: [{
                        label: 'Immediately',
                        value: false,
                        title: 'Place Orders Immediately'
                    }, {
                        label: 'At a scheduled time',
                        value: true,
                        title: 'Run Orders at a future time'
                    }]
                })
            }));
            this.deliverySchedule.currentView.turnOnLimitedWidth();
            this.handleSchedulingDeliveryValue();

            this.timePicker.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('deliveryTime')],
                    id: '',
                    placeholder: 'Time for delivery to take effect',
                    type: 'TIME',
                    showLabel: false
                })
            }))
            this.timePicker.currentView.turnOnLimitedWidth();

            const possibleEnumValues = user.get('user').getPreferences().get('deliveryMethods')
                .map(function(val) {
                    return {
                        label: val.get('name'), 
                        value: val.get('deliveryId'), 
                        class: ''
                    }
                });
            
            this.deliveryPicker.show(new PropertyView({
                model: new Property({
                    enumFiltering: false,
                    showValidationIssues: false,
                    enumMulti: true,
                    enum: possibleEnumValues,
                    value: [this.model.get('deliveryIds')],
                    id: 'Delivery Method'
                })
            }));
            this.deliveryPicker.currentView.turnOnLimitedWidth();
        },
        turnOnEditing() {
            this.$el.addClass('is-editing');
            this.regionManager.forEach(function(region) {
                if (region.currentView) {
                    region.currentView.turnOnEditing();
                }
            });
        },
        turnOffEditing() {
            this.regionManager.forEach(function(region) {
                if (region.currentView) {
                    region.currentView.turnOffEditing();
                }
            });
        },
        handleSchedulingDeliveryValue() {
            const isSchedulingDelivery = this.deliverySchedule.currentView.model.getValue()[0];
            this.$el.toggleClass('is-scheduled', isSchedulingDelivery);
        },
        getDataModel() {
            this.model.set({
                deliveryScheduled: this.deliverySchedule.currentView.model.getValue()[0],
                deliveryTime: this.timePicker.currentView.model.getValue()[0],
                deliveryIds: this.deliveryPicker.currentView.model.getValue()[0]
            });
            return this.model;
        }
    })
});