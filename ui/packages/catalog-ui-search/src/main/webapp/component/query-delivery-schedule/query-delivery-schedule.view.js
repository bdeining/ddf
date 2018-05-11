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
            enableDeliverySchedule: '.enable-delivery-scheduling',
            scheduleProperties: '.delivery-schedule-properties',
            intervalPicker: '.interval-picker',
            unitPicker: '.delivery-unit-picker',
            startPicker: '.delivery-start-picker',
            endPicker: '.delivery-end-picker',
            deliveryPicker: '.delivery-method-picker'
        },
        initialize() {
            const possibleModel = this.model && this.model.id ? store.getDeliveryById(this.model.id) : this.model;
            this.model = possibleModel || this.model
        },
        onBeforeShow() {
            this.turnOffEditing();
            this.setupRegions();
            this.listenTo(this.enableDeliverySchedule.currentView.model, 'change:value', this.handleSchedulingDeliveryValue);
            this.listenTo(this.intervalPicker.currentView.model, 'change:value', this.handleIntevalPickerValue);
            this.turnOnEditing();
        },
        setupRegions() {
            this.enableDeliverySchedule.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('isScheduled')],
                    id: 'Delivery Schedule',
                    radio: [{
                        label: 'On',
                        value: true,
                        title: 'Activate Query Delivery'
                    }, {
                        label: 'Off',
                        value: false,
                        title: 'Deactivate Query Delivery'
                    }]
                })
            }));
            this.enableDeliverySchedule.currentView.turnOnLimitedWidth();
            this.handleSchedulingDeliveryValue();

            this.intervalPicker.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('scheduleInterval')],
                    id: '',
                    type: 'INTEGER',
                    showValidationIssues: false,
                    showLabel: false
                })
            }));
            this.intervalPicker.currentView.turnOnLimitedWidth();

            this.unitPicker.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('scheduleUnit')],
                    id: '',
                    enum: [{
                        label: 'Months', 
                        value: 'months'
                    }, {
                        label: 'Weeks',
                        value: 'weeks'
                    }, {
                        label: 'Days',
                        value: 'days'
                    }, {
                        label: 'Hours',
                        value: 'hours'
                    }, {
                        label: 'Minutes',
                        value: 'minutes'
                    }],
                    showValidationIssues: false,
                    showLabel: false
                })
            }));
            this.unitPicker.currentView.turnOnLimitedWidth();

            this.startPicker.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('scheduleStart')],
                    id: 'Starts on',
                    placeholder: 'Date and time for query to take effect',
                    type: 'DATE'
                })
            }));
            this.startPicker.currentView.turnOnLimitedWidth();

            this.endPicker.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('scheduleEnd')],
                    id: 'Ends on',
                    placeholder: 'Date and time for query to stop',
                    type: 'DATE'
                })
            }));
            this.endPicker.currentView.turnOnLimitedWidth();

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
            //Hide the current properties view and show the one from delivery?
            const isSchedulingDelivery = this.enableDeliverySchedule.currentView.model.getValue()[0];
            this.$el.toggleClass('is-scheduled', isSchedulingDelivery);
        },
        handleIntervalPickerValue() {
            var currVal = this.intervalPicker.currentView.model.getValue()[0];
            if (currVal < 0) {
                console.log('Hey currval is less than 0. changing model value to be [0]');
                this.intervalPicker.currentView.model.setValue([0]);
            }
        },
        getDataModel() {
            this.model.set({
                isScheduled: this.enableDeliverySchedule.currentView.model.getValue()[0],
                scheduleInterval: this.intervalPicker.currentView.model.getValue()[0],
                scheduleUnit: this.unitPicker.currentView.model.getValue()[0],
                scheduleStart: this.startPicker.currentView.model.getValue()[0],
                scheduleEnd: this.endPicker.currentView.model.getValue()[0],
                deliveryIds: this.deliveryPicker.currentView.model.getValue()[0]
            });
            return this.model;
        }
    })
});